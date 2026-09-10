// AC-1.13: NFR-1 and NFR-2, measured against real endpoints.
//
// One step of a ramp. The driver (run-nfr-calibration.sh) steps the rate and
// decides where the knee is; this script holds one rate for one duration and
// reports what happened.
//
// WHAT EACH RAMP SETS
//
//   ENDPOINT=search  ramp until p99 breaches NFR-5's 50 ms   -> NFR-1
//   ENDPOINT=hold    ramp until p99 breaches NFR-3's 150 ms  -> NFR-2
//
// These are NOT AC-0.7's numbers. That measured /actuator/health with no domain
// work in the path and found 750 rps; these endpoints do real work and will be a
// fraction of it. The ratio between them is the cost of the domain path, and
// explaining it is a §9.4 input.

import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import {
  search,
  hold,
  passengersFor,
  spreadTarget,
  generalTarget,
  outcomeSummary,
  tokensFor,
  useToken,
  USERS,
} from '../lib/api.js';

const ENDPOINT = __ENV.ENDPOINT || 'search';
const RATE = parseInt(__ENV.RATE, 10);
const DURATION = __ENV.DURATION || '30s';
const PASSENGERS = parseInt(__ENV.PASSENGERS || '2', 10);
const OUT = __ENV.OUT || '';

const DURATION_SECONDS = parseInt(DURATION, 10) || 30;

/**
 * How many virtual users this step needs.
 *
 * §19.1: "at P1's 5,000 VUs that is 1:1, so neither FR-60's 10 rps cap nor
 * FR-20's 3-hold limit binds during benchmarks." That is a REQUIREMENT ON THE VU
 * COUNT, not an observation, and both limits have to be satisfied:
 *
 *   FR-60 - 10 requests per second per user, so VUs >= rate / 10.
 *   FR-20 - 3 ACTIVE holds per user. A hold lives 120 s (FR-17), so every hold
 *           made during a 30 s step is still active at the end of it: each VU may
 *           make at most 3 for the whole step, giving VUs >= rate * seconds / 3.
 *
 * The second dominates by two orders of magnitude and is easy to miss - at 250
 * rps for 30 s it needs 2,500 VUs, not 250. Under-provision and the step fills
 * with TOO_MANY_HOLDS, which is a correct system response and a useless
 * measurement: it reports the cost of counting a user's open holds as the cost of
 * allocating a berth.
 */
function requiredVus() {
  // rate / 5, not rate / 10. FR-60's cap is 10 rps per user and a two-bucket
  // sliding window estimates fractionally above the instantaneous rate, so
  // sitting exactly on the cap trips it: a 500 rps search step with 50 VUs -
  // precisely 10 rps each - collected 71 RATE_LIMITED and voided under §19.5.
  // Half the cap leaves the estimator room to be imprecise without binding.
  const forRateLimit = Math.ceil(RATE / 5);
  const forHoldLimit =
    ENDPOINT === 'hold' ? Math.ceil((RATE * DURATION_SECONDS) / 3) : 0;
  return Math.max(50, forRateLimit, forHoldLimit);
}

const NEEDED = requiredVus();
const VUS = Math.min(NEEDED, USERS.count);

if (NEEDED > USERS.count) {
  // Loud, at init, rather than a puzzling wall of 429s later. §19.5 voids the run
  // either way; this says why while there is still time to seed more users.
  console.warn(
    `HARNESS UNDER-PROVISIONED: ${ENDPOINT} at ${RATE} rps for ${DURATION} needs ` +
      `${NEEDED} distinct users, and only ${USERS.count} are seeded (FR-69). ` +
      `FR-20's 3-hold limit or FR-60's rate cap will bind and §19.5 voids the run.`,
  );
}

export const options = {
  scenarios: {
    step: {
      // constant-arrival-rate, NOT an iteration-based executor. An iteration
      // executor waits for each response and therefore slows down as the system
      // does, hiding the knee this test exists to find - it reports "fine" right
      // up until it reports nothing.
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: VUS,
      // Never above the seeded user table: a VU beyond it shares a user id with
      // an earlier one, which is the exact condition §19.1's 1:1 rule forbids.
      maxVUs: VUS,
    },
  },
  // Recorded, not enforced. This run is a MEASUREMENT: a breached threshold is
  // the answer, not an error.
  thresholds: {
    'http_req_duration{name:search}': [{ threshold: 'p(99)<50', abortOnFail: false }],
    'http_req_duration{name:hold}': [{ threshold: 'p(99)<150', abortOnFail: false }],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // The token call is setup cost, not the thing being measured. Excluded from the
  // reported trend by tag rather than by not making the call - it still happens,
  // and its cost still competes for the same eight cores.
  discardResponseBodies: false,
};

/**
 * Every VU's token, before the clock starts.
 *
 * setup() is not attributed to any metric, which is exactly where the cost of
 * authenticating several hundred virtual users belongs. Fetching them lazily made
 * an identical step read 50.9 ms p99 once and 541.8 ms another time.
 */
export function setup() {
  return { tokens: tokensFor(VUS) };
}

export default function (data) {
  useToken(data.tokens);
  const i = exec.scenario.iterationInTest;

  if (ENDPOINT === 'search') {
    search(spreadTarget(i));
    return;
  }

  // GENERAL only. A closed Tatkal window answers QUOTA_LOCKED before touching the
  // allocator, which is a correct outcome and a much cheaper one - a ramp that
  // collected them would report the cost of a clock comparison as write
  // throughput.
  const target = generalTarget(i);

  // Unique per attempt. FR-19 replays a repeated key instead of allocating, and a
  // replay is a fast 200 that would look like throughput.
  const key = `nfr-${exec.vu.idInTest}-${i}-${__ENV.RUN_ID || '0'}`;
  hold(target, key, passengersFor(PASSENGERS));
}

export function handleSummary(data) {
  const trend = data.metrics[`http_req_duration{name:${ENDPOINT}}`];
  const d = trend ? trend.values : {};
  const reqs = data.metrics.http_reqs ? data.metrics.http_reqs.values : {};
  const dropped = data.metrics.dropped_iterations
    ? data.metrics.dropped_iterations.values.count
    : 0;

  const result = Object.assign(
    {
      endpoint: ENDPOINT,
      requested_rps: RATE,
      // http_reqs includes the token calls, so achieved rate is taken from the
      // scenario's own iteration count rather than from request count.
      achieved_rps: data.metrics.iterations
        ? data.metrics.iterations.values.rate || 0
        : 0,
      total_requests_rps: reqs.rate || 0,
      p50_ms: d.med || 0,
      p95_ms: d['p(95)'] || 0,
      p99_ms: d['p(99)'] || 0,
      max_ms: d.max || 0,
      // Non-zero means k6 could not issue requests fast enough. The step then
      // measured the LOAD GENERATOR, and the driver voids it - §19.5's rule one
      // layer down.
      dropped_iterations: dropped,
      vus_max: data.metrics.vus_max ? data.metrics.vus_max.values.max : 0,
    },
    outcomeSummary(data),
  );

  const out = {};
  out['stdout'] = JSON.stringify(result) + '\n';
  if (OUT) {
    out[OUT] = JSON.stringify(result, null, 2);
  }
  return out;
}
