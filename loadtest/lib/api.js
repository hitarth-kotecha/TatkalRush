// Shared harness for every §19 profile.
//
// Three things live here because getting any of them wrong makes a run look
// healthy while measuring something else.
//
// 1. ONE VU : ONE SYNTHETIC USER (§19.1). FR-60 caps each user at 10 rps, so a
//    harness that reused a handful of users would throttle itself and §19.5 would
//    void the run. The mapping is arithmetic on the VU id, not a random pick,
//    so it is exact rather than probable.
//
// 2. FR-51's SPLIT. k6's built-in http_req_failed counts any 4xx as a failure.
//    SEAT_UNAVAILABLE is a CORRECT outcome - it is what a sold-out train is
//    supposed to say - so left alone a perfectly healthy spike reports a 90%
//    error rate and the report is worthless. Outcomes are classified on the
//    §11.2 error CODE, never on the status.
//
// 3. RATE_LIMITED IS NOT AN ERROR RATE, IT IS A VOID. §19.5: a single occurrence
//    means requests were rejected at the edge before reaching the system under
//    test, so throughput would be reported for load that was never served.

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';

// SharedArray: parsed once and shared across VUs. At 5,000 VUs a per-VU copy of
// 1,200 targets is memory the load generator does not have on a box that is also
// running the system under test (NFR-13).
const catalogue = new SharedArray('targets', function () {
  const doc = JSON.parse(open('../targets.json'));
  return doc.targets;
});

const meta = new SharedArray('meta', function () {
  const doc = JSON.parse(open('../targets.json'));
  return [{ users: doc.users, journeyDates: doc.journeyDates }];
});

export const USERS = meta[0].users;

// ── outcome counters ────────────────────────────────────────────────────────

export const okHolds = new Counter('holds_ok');
export const seatUnavailable = new Counter('holds_seat_unavailable');
export const quotaLocked = new Counter('holds_quota_locked');
export const chartPrepared = new Counter('holds_chart_prepared');
export const tooManyHolds = new Counter('holds_too_many');

/** §19.5's gate. Non-zero VOIDS the run; it is not an error rate to report. */
export const rateLimited = new Counter('rate_limited_total');

/** Anything the system should never do: 5xx, a timeout, an unclassified code. */
export const failures = new Counter('request_failures');

export const searchOk = new Counter('search_ok');

// ── identity ────────────────────────────────────────────────────────────────

/**
 * This VU's synthetic user (FR-69, §19.1).
 *
 * idInTest is 1-based and unique across the whole run, so the mapping is
 * one-to-one for any VU count up to the seeded user table. Above it the modulo
 * wraps and users start sharing - which is exactly the condition that makes
 * FR-60 bind, so the driver checks the count rather than letting it happen.
 */
export function userId() {
  return USERS.firstId + ((exec.vu.idInTest - 1) % USERS.count);
}

/** One token, fetched from FR-58's stub issuer. */
export function fetchToken(id) {
  const res = http.post(
    `${BASE}/api/v1/auth/token`,
    JSON.stringify({ userId: id }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'token' } },
  );
  if (res.status !== 200) {
    // Fail loudly rather than proceeding unauthenticated: every later request
    // would 401, and a run of 401s at full rate reads as a fast, healthy system.
    throw new Error(`token request failed for user ${id}: ${res.status} ${res.body}`);
  }
  return JSON.parse(res.body).token;
}

/**
 * Every VU's token, fetched once in setup().
 *
 * <p><b>This belongs outside the measurement window, and putting it inside cost a
 * measurement.</b> The first version fetched lazily on each VU's first iteration.
 * k6 pre-allocates VUs, so a step needing 667 of them opened with a burst of ~500
 * simultaneous token requests competing with the very requests being timed - and
 * an identical 100 rps hold step read p99 50.9 ms once and 541.8 ms another time
 * depending on how many VUs were already warm from the step before.
 *
 * <p>setup() runs before the scenario starts and its cost is not attributed to
 * any metric, so the burst still happens - it just happens somewhere honest.
 */
export function tokensFor(vuCount) {
  const tokens = [];
  for (let i = 0; i < vuCount; i++) {
    tokens.push(fetchToken(USERS.firstId + (i % USERS.count)));
  }
  return tokens;
}

// Per-VU: k6 gives each virtual user its own JS runtime, so this holds the one
// token handed to this VU by setup data.
let myToken = null;

export function useToken(tokens) {
  if (myToken === null) {
    myToken = tokens[(exec.vu.idInTest - 1) % tokens.length];
  }
}

export function authHeaders() {
  if (myToken === null) {
    // A profile that forgot to call useToken. Louder than a 401 storm.
    throw new Error('no token: call useToken(data.tokens) at the top of the default function');
  }
  return { Authorization: `Bearer ${myToken}`, 'Content-Type': 'application/json' };
}

// ── target selection ────────────────────────────────────────────────────────

/** Every (schedule, class, quota) on a live schedule. */
export function targets() {
  return catalogue;
}

/**
 * GENERAL-only targets, precomputed.
 *
 * Built as its own SharedArray rather than filtered per call: a filter over 1,200
 * entries on every iteration is work the load generator does instead of issuing
 * load, and at 250 rps that competes for the same eight cores as the system under
 * test (NFR-13).
 */
const generalOnly = new SharedArray('general', function () {
  const doc = JSON.parse(open('../targets.json'));
  return doc.targets.filter(function (t) {
    return t.quota_type === 'GENERAL';
  });
});

/**
 * An index spread across a catalogue of the given size.
 *
 * 7919 is prime and coprime with any plausible catalogue length, so consecutive
 * VU ids land far apart rather than adjacent. Adjacency matters here: the
 * catalogue is ordered by schedule, so neighbouring entries are the same train's
 * other classes, and a burst of in-flight iterations hitting neighbours would
 * concentrate on one schedule.
 */
function spreadIndex(iteration, size) {
  return (exec.vu.idInTest * 7919 + iteration) % size;
}

/**
 * A target spread across the catalogue.
 *
 * Deliberately spread rather than pinned. Holds CONSUME inventory, so a ramp
 * against one pool exhausts ~250 berths in seconds and then measures the
 * SEAT_UNAVAILABLE path - a free-count read returning zero, far cheaper than an
 * allocation - and reports that as the system's write throughput.
 *
 * Concentrating load on one partition is a different measurement, and it is P3's.
 */
export function spreadTarget(iteration) {
  return catalogue[spreadIndex(iteration, catalogue.length)];
}

/**
 * GENERAL pools only, for ramps that must not meet a closed Tatkal window.
 *
 * <p>THIS USED TO WALK FORWARD FROM {@code catalogue.indexOf(t)}, AND THAT IS A
 * TRAP. A k6 SharedArray deserializes a fresh object on every access, so
 * reference equality never holds and indexOf returns -1 - every walk started from
 * index 0 and returned the FIRST general pool in the catalogue. Half the targets
 * are TATKAL, so half of all load landed on one pool: 122 of 1,116 holds in a
 * calibration step, against a pool of 129 berths, producing a wall of
 * SEAT_UNAVAILABLE and a p99 of 2.7 s that looked like the system's knee.
 *
 * <p>A profile that looks like it spreads and does not is precisely what §19.5
 * exists to prevent, one layer below the rules it states.
 */
export function generalTarget(iteration) {
  return generalOnly[spreadIndex(iteration, generalOnly.length)];
}

// ── requests ────────────────────────────────────────────────────────────────

export function search(target) {
  const url =
    `${BASE}/api/v1/trains/search?from=${target.from_code}&to=${target.to_code}` +
    `&date=${target.journey_date}&class=${target.travel_class}`;

  const res = http.get(url, { headers: authHeaders(), tags: { name: 'search' } });
  classify(res, { search: true });
  return res;
}

/**
 * @param key the Idempotency-Key (FR-19). Must be unique per logical attempt, or
 *   the second request replays the first instead of allocating - which returns
 *   HTTP 200 quickly and would look like throughput.
 */
export function hold(target, key, passengers) {
  const body = JSON.stringify({
    scheduleId: target.schedule_id,
    travelClass: target.travel_class,
    quotaType: target.quota_type,
    fromStationCode: target.from_code,
    toStationCode: target.to_code,
    passengers: passengers,
  });

  const res = http.post(`${BASE}/api/v1/bookings/hold`, body, {
    headers: Object.assign(authHeaders(), { 'Idempotency-Key': key }),
    tags: { name: 'hold' },
  });
  classify(res, { search: false });
  return res;
}

export function passengersFor(count) {
  const list = [];
  for (let i = 0; i < count; i++) {
    list.push({ name: `LoadTest ${exec.vu.idInTest}-${i}`, age: 30 + i, gender: 'M' });
  }
  return list;
}

// ── classification ──────────────────────────────────────────────────────────

/**
 * FR-51: a correct outcome and a failure are different things, and only the error
 * code distinguishes them. 409 is SEAT_UNAVAILABLE (correct) and also
 * IDEMPOTENCY_KEY_REUSED (a harness bug); 429 is QUEUE_REQUIRED (correct) and
 * also RATE_LIMITED (voids the run).
 */
function classify(res, opts) {
  if (res.status === 200 || res.status === 201) {
    if (opts.search) {
      searchOk.add(1);
    } else {
      okHolds.add(1);
    }
    return;
  }

  if (res.status === 0 || res.status >= 500) {
    // status 0 is a k6-side timeout or connection failure. Counted as a system
    // failure rather than ignored: at the knee it is the first thing that moves.
    failures.add(1);
    return;
  }

  let code = null;
  try {
    code = JSON.parse(res.body).code;
  } catch (e) {
    code = null;
  }

  switch (code) {
    case 'SEAT_UNAVAILABLE':
      seatUnavailable.add(1);
      return;
    case 'QUOTA_LOCKED':
      quotaLocked.add(1);
      return;
    case 'CHART_PREPARED':
      chartPrepared.add(1);
      return;
    case 'TOO_MANY_HOLDS':
      // FR-20's 3-hold limit. Correct behaviour, but during a benchmark it means
      // the profile is holding faster than they expire - a harness shape problem,
      // like RATE_LIMITED but not a void.
      tooManyHolds.add(1);
      return;
    case 'RATE_LIMITED':
      rateLimited.add(1);
      return;
    default:
      // Unclassified: INVALID_REQUEST, NOT_FOUND, IDEMPOTENCY_KEY_REUSED, or an
      // unparseable body. All of them mean the harness or the system is wrong,
      // and none of them should be quietly folded into a "legitimate" bucket.
      failures.add(1);
  }
}

/** Summary shape shared by the ramp driver and the profile reports. */
export function outcomeSummary(data) {
  const c = (name) => (data.metrics[name] ? data.metrics[name].values.count || 0 : 0);
  return {
    search_ok: c('search_ok'),
    holds_ok: c('holds_ok'),
    seat_unavailable: c('holds_seat_unavailable'),
    quota_locked: c('holds_quota_locked'),
    chart_prepared: c('holds_chart_prepared'),
    too_many_holds: c('holds_too_many'),
    rate_limited: c('rate_limited_total'),
    failures: c('request_failures'),
  };
}
