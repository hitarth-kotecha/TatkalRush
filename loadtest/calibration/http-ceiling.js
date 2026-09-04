// AC-0.7 part 2: the machine's HTTP ceiling (DD-019).
//
// WHAT THIS MEASURES, AND WHY IT IS NOT NFR-1 OR NFR-2.
//
// This drives an endpoint that does no domain work, through the real topology
// (nginx round-robin over two app replicas). What comes back is the hardware and
// framework ceiling: an UPPER BOUND that NFR-1 and NFR-2 can never exceed,
// obtainable in Phase 0 before a single business rule exists.
//
// The real NFR-1 and NFR-2 are set later by AC-1.13, against `search` and `hold`.
// Reporting this number as though it were throughput would be dishonest - real
// endpoints are always a fraction of a health check.
//
// TWO TARGETS, deliberately:
//
//   /actuator/health/liveness - app state only. No I/O. The pure HTTP path.
//   /actuator/health          - also checks Postgres, Redis, disk and SSL, so it
//                               includes one backend round trip.
//
// The gap between them is the cost of touching a backend at all, and it is worth
// knowing before Phase 1 attributes latency to the allocator.
//
// Run via run-calibration.sh, which handles warmup and steps through rates.

import http from 'k6/http';
import { check } from 'k6';

const TARGET = __ENV.TARGET;
const RATE = parseInt(__ENV.RATE, 10);
const DURATION = __ENV.DURATION || '20s';
const OUT = __ENV.OUT || '';

export const options = {
  scenarios: {
    step: {
      // constant-arrival-rate, NOT a VU-count executor. An iteration-based
      // executor slows down as the system does, which hides the very knee this
      // test exists to find: it would report "the system is fine" right up until
      // it reported nothing at all.
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // Generous headroom so k6 never becomes the bottleneck. If VUs are
      // exhausted, dropped_iterations rises and the driver flags the run.
      preAllocatedVUs: Math.min(Math.max(RATE, 50), 1000),
      maxVUs: 2000,
    },
  },
  // Thresholds are recorded, not enforced: this run is a MEASUREMENT, and a
  // failed threshold here is the answer rather than an error.
  thresholds: {
    'http_req_duration': [{ threshold: 'p(99)<50', abortOnFail: false }],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const res = http.get(TARGET);
  check(res, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const d = data.metrics.http_req_duration ? data.metrics.http_req_duration.values : {};
  const reqs = data.metrics.http_reqs ? data.metrics.http_reqs.values : {};
  const failed = data.metrics.http_req_failed ? data.metrics.http_req_failed.values : {};
  const dropped = data.metrics.dropped_iterations
    ? data.metrics.dropped_iterations.values.count
    : 0;

  const result = {
    target: TARGET,
    requested_rps: RATE,
    achieved_rps: reqs.rate || 0,
    p50_ms: d.med || 0,
    p95_ms: d['p(95)'] || 0,
    p99_ms: d['p(99)'] || 0,
    max_ms: d.max || 0,
    failed_rate: failed.rate || 0,
    // Non-zero means k6 could not issue requests fast enough. The run is then a
    // measurement of the LOAD GENERATOR, not of the system, and must be
    // discarded rather than reported - the same trap as SDD 19.5's rate-limit
    // rule, one layer lower.
    dropped_iterations: dropped,
  };

  const out = {};
  out['stdout'] = JSON.stringify(result) + '\n';
  if (OUT) {
    out[OUT] = JSON.stringify(result, null, 2);
  }
  return out;
}
