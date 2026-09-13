// P2 - Sustained mixed (§19.1).
//
//   "2,000 rps, 90% search / 10% book, 10 min"
//
// The 2,000 is v1.2's 16 GB reference figure; P2's sustained rate IS NFR-1, which
// AC-1.13 measured at 550 rps against `search` on this machine.
//
// WHAT P2 IS FOR. AC-1.3: "P2 meets NFR-1, NFR-3, NFR-5, and is a VALID run under
// §19.5". Three separate claims, and the third is the one that is easy to fake:
//
//   NFR-1  the sustained rate is actually achieved, with no dropped iterations
//   NFR-3  hold p99 <= 150 ms at that load
//   NFR-5  search p99 <= 50 ms at that load
//   §19.5  rate_limited_total == 0, no constraint violations, §14 all passing
//
// The 90/10 split is per ITERATION, not per VU. Splitting by VU would give the
// booking tenth its own dedicated users and let the searching nine-tenths run
// without ever contending for a connection with a write - which is a different
// workload wearing the same ratio.

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

const RATE = parseInt(__ENV.RATE || '550', 10);
const DURATION_MINUTES = parseFloat(__ENV.DURATION_MINUTES || '10');
const WRITE_FRACTION = parseFloat(__ENV.WRITE_FRACTION || '0.1');
const HOLD_TTL_SECONDS = parseInt(__ENV.HOLD_TTL_SECONDS || '15', 10);
const OUT = __ENV.OUT || '';

const HOLD_RATE = RATE * WRITE_FRACTION;

/**
 * Sized so neither FR-60 nor FR-20 binds - see p1-tatkal-spike.js for the
 * derivation. Here the write fraction does the work: only a tenth of iterations
 * are holds, so the hold-limit term is a tenth of what P1's is at the same total
 * rate.
 *
 * At FR-17's default 120 s TTL this needs 2,200 VUs for 550 rps, which is more
 * JavaScript runtimes than this box has memory for (AC-1.13 dropped iterations at
 * 1,334). DD-044 shortens the TTL for benchmark runs; the report declares it.
 *
 * With the same 1.5x headroom as P1, for the same reason: the inequality is a mean,
 * and a VU sized to sit exactly on FR-20's limit exceeds it whenever k6 hands it an
 * iteration slightly early.
 */
const USER_HEADROOM = 1.5;
const VUS = Math.min(
  Math.max(
    50,
    Math.ceil(((HOLD_RATE * HOLD_TTL_SECONDS) / 3) * USER_HEADROOM),
    Math.ceil(RATE / 5),
  ),
  USERS.count,
);

export const options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_MINUTES}m`,
      preAllocatedVUs: VUS,
      maxVUs: VUS,
    },
  },
  thresholds: {
    'http_req_duration{name:search}': [{ threshold: 'p(99)<50', abortOnFail: false }],
    'http_req_duration{name:hold}': [{ threshold: 'p(99)<150', abortOnFail: false }],
    // §19.5's gate, as a threshold rather than a note. A run with even one is
    // invalid, and the generator refuses to publish it.
    rate_limited_total: [{ threshold: 'count==0', abortOnFail: false }],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { tokens: tokensFor(VUS) };
}

export default function (data) {
  useToken(data.tokens);
  const i = exec.scenario.iterationInTest;

  // Deterministic, not random: every tenth iteration writes. A PRNG would give
  // the same ratio in expectation and a different one in every run, and two
  // benchmark runs should differ in their numbers and nowhere else.
  const isWrite = i % Math.round(1 / WRITE_FRACTION) === 0;

  if (isWrite) {
    const target = generalTarget(i);
    hold(target, `p2-${exec.vu.idInTest}-${i}-${__ENV.RUN_ID || '0'}`, passengersFor(2));
  } else {
    search(spreadTarget(i));
  }
}

export function handleSummary(data) {
  const trend = (name) =>
    data.metrics[`http_req_duration{name:${name}}`]
      ? data.metrics[`http_req_duration{name:${name}}`].values
      : {};
  const s = trend('search');
  const h = trend('hold');

  const result = Object.assign(
    {
      profile: 'P2',
      requested_rps: RATE,
      duration_minutes: DURATION_MINUTES,
      write_fraction: WRITE_FRACTION,
      hold_ttl_seconds: HOLD_TTL_SECONDS,
      vus: VUS,
      achieved_rps: data.metrics.iterations ? data.metrics.iterations.values.rate || 0 : 0,
      search_p50_ms: s.med || 0,
      search_p95_ms: s['p(95)'] || 0,
      search_p99_ms: s['p(99)'] || 0,
      hold_p50_ms: h.med || 0,
      hold_p95_ms: h['p(95)'] || 0,
      hold_p99_ms: h['p(99)'] || 0,
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
