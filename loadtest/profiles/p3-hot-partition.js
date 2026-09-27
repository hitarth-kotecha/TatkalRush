// P3 - Hot partition (§19.1).
//
//   "100% of P1 load onto ONE (train, date, class)"
//
// Same spike shape as P1 - §19.1 is explicit that P3 scales from P1 by the same
// ratio, because it's the same load, aimed differently. The only change from
// p1-tatkal-spike.js is the target: every iteration hits hotTarget()'s single
// fixed pool instead of spreading across every open TATKAL pool.
//
// WHAT P3 IS FOR. §19.1 calls it "the strategy discriminator" and AC-2.3 is the
// gate: run this against both allocator strategies and the results must differ
// MEASURABLY, with the difference explained mechanistically. P1 spreads load
// across ~dozens of TATKAL pools, so no single partition sees more than a
// fraction of it - which is exactly the load Strategy A's per-key Redis atomicity
// and Strategy B's one-owner-per-partition model both handle without contention
// on each other. Concentrating everything onto one pool is what forces a
// single point of serialised writes and gives the two strategies a chance to
// disagree.
//
// AC-1.11 applies here just as it does to P1: the stack must be started with
// TATKAL_CLOCK_OFFSET so the target's journey date's window is open, or every
// request answers QUOTA_LOCKED before the allocator is reached and this measures
// a clock comparison instead of contention.

import exec from 'k6/execution';
import {
  hold,
  passengersFor,
  hotTarget,
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

// Identical derivation to P1 (see p1-tatkal-spike.js) - sizing is about hold
// rate per user against FR-20/FR-60, not about which pool is targeted.
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
      // Same shape rationale as P1: arrival rate, not VU count, ramps - a spike
      // that slows its own arrival when the system does is not measuring the
      // system anymore.
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
    // NFR-4, same budget as P1 - P3 is P1's load, not a different one.
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

  // The one difference from P1: hotTarget() is the same pool for every VU and
  // every iteration, never spread. That concentration is the entire point of
  // this profile - see the file header.
  const target = hotTarget();
  hold(target, `p3-${exec.vu.idInTest}-${i}-${__ENV.RUN_ID || '0'}`, passengersFor(1));
}

export function handleSummary(data) {
  const d = data.metrics['http_req_duration{name:hold}']
    ? data.metrics['http_req_duration{name:hold}'].values
    : {};

  const result = Object.assign(
    {
      profile: 'P3',
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
