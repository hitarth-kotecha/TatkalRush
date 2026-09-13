// P1 - Tatkal spike (§19.1).
//
//   "0 -> 5,000 VU arrival rate over 10 s, hold 30 s, ramp down"
//
// The 5,000 is v1.2's 16 GB reference figure. §19.1 says the magnitudes are
// calibrated rather than fixed, and P1's spike rate IS NFR-2 - which AC-1.13 set
// to a FLOOR of 150 rps, because the load generator reached its limit before the
// endpoint did. So this ramps to 150 rps and its first obligation is to report
// whether it, too, was harness-bound.
//
// WHAT P1 IS FOR. AC-1.2 gates on correctness, not throughput: "P1 completes with
// zero INV violations and zero allocation_constraint_violations_total". The point
// is contention - many callers racing for the same berths - and the numbers are
// context for the invariant check that follows.
//
// AC-1.11: P1 runs against an OPEN Tatkal window. That needs the stack started
// with TATKAL_CLOCK_OFFSET set, because the seeded dataset's windows all open in
// the future (DD-041). A closed window answers QUOTA_LOCKED before touching the
// allocator, and a spike of those measures a clock comparison.

import exec from 'k6/execution';
import {
  hold,
  passengersFor,
  tatkalTarget,
  outcomeSummary,
  tokensFor,
  useToken,
  USERS,
} from '../lib/api.js';

const PEAK = parseInt(__ENV.PEAK_RPS || '150', 10);
const HOLD_SECONDS = parseInt(__ENV.HOLD_SECONDS || '30', 10);
const RAMP_SECONDS = parseInt(__ENV.RAMP_SECONDS || '10', 10);
const HOLD_TTL_SECONDS = parseInt(__ENV.HOLD_TTL_SECONDS || '15', 10);
const OUT = __ENV.OUT || '';

/**
 * §19.1's 1 VU : 1 user rule, sized so neither FR-60 nor FR-20 binds.
 *
 * FR-20 allows 3 ACTIVE holds per user, and a hold stays active for its TTL. At
 * the spike's peak a VU makes PEAK/VUs holds per second, so:
 *
 *     (PEAK / VUs) * ttlSeconds <= 3
 *
 * At FR-17's default 120 s TTL and NFR-2's 150 rps floor that needs 6,000 users -
 * more than FR-69 seeds. The profile therefore runs with a shortened TTL
 * (DD-044), which is declared in the report because it puts lazy reaping inside
 * the measured window.
 *
 * The inequality is a MEAN, and sizing to it exactly is sizing every VU to sit on
 * FR-20's limit. k6 hands iterations to whichever VU is free, not strictly in
 * turn, so some VUs run a little ahead of the average - and at exactly
 * rate * ttl / 3 the first P1 run with working routing got 62 TOO_MANY_HOLDS from
 * users taking their fourth hold a few milliseconds before their first expired.
 * USER_HEADROOM puts the mean at two holds per TTL instead of three.
 */
const USER_HEADROOM = 1.5;
const VUS = Math.min(
  Math.max(
    50,
    Math.ceil(((PEAK * HOLD_TTL_SECONDS) / 3) * USER_HEADROOM),
    Math.ceil(PEAK / 5),
  ),
  USERS.count,
);

export const options = {
  scenarios: {
    spike: {
      // ramping-arrival-rate: the SHAPE is the profile. A spike that ramps its
      // VU count instead of its arrival rate would slow down exactly when the
      // system does, which is the opposite of what a spike is meant to expose.
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: VUS,
      maxVUs: VUS,
      stages: [
        { target: PEAK, duration: `${RAMP_SECONDS}s` },
        { target: PEAK, duration: `${HOLD_SECONDS}s` },
        { target: 0, duration: `${RAMP_SECONDS}s` },
      ],
    },
  },
  thresholds: {
    // NFR-4: hold p99 <= 800 ms AT SPIKE. A different budget from NFR-3's 150 ms,
    // which applies at NFR-1's sustained load - the SDD allows a spike to be
    // slower, and conflating the two would either fail a healthy spike or pass a
    // sick steady state.
    'http_req_duration{name:hold}': [{ threshold: 'p(99)<800', abortOnFail: false }],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { tokens: tokensFor(VUS) };
}

export default function (data) {
  useToken(data.tokens);
  const i = exec.scenario.iterationInTest;

  // TATKAL pools only. That is what makes this P1 rather than a generic write
  // spike: FR-9 sizes a Tatkal pool at ceil(0.10 x capacity), so the same rate
  // meets roughly a tenth of the inventory and contention is an order of
  // magnitude higher.
  const target = tatkalTarget(i);
  hold(target, `p1-${exec.vu.idInTest}-${i}-${__ENV.RUN_ID || '0'}`, passengersFor(1));
}

export function handleSummary(data) {
  const d = data.metrics['http_req_duration{name:hold}']
    ? data.metrics['http_req_duration{name:hold}'].values
    : {};

  const result = Object.assign(
    {
      profile: 'P1',
      peak_rps: PEAK,
      shape: `0 -> ${PEAK} over ${RAMP_SECONDS}s, hold ${HOLD_SECONDS}s, ramp down ${RAMP_SECONDS}s`,
      hold_ttl_seconds: HOLD_TTL_SECONDS,
      vus: VUS,
      achieved_rps: data.metrics.iterations ? data.metrics.iterations.values.rate || 0 : 0,
      p50_ms: d.med || 0,
      p95_ms: d['p(95)'] || 0,
      p99_ms: d['p(99)'] || 0,
      max_ms: d.max || 0,
      // The first thing to check. A non-zero count means this run measured k6.
      dropped_iterations: data.metrics.dropped_iterations
        ? data.metrics.dropped_iterations.values.count
        : 0,
    },
    outcomeSummary(data),
  );

  const out = { stdout: JSON.stringify(result) + '\n' };
  if (OUT) {
    out[OUT] = JSON.stringify(result, null, 2);
  }
  return out;
}
