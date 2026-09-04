#!/usr/bin/env bash
# AC-0.7 part 2 driver (DD-019).
#
# Steps through increasing request rates against an endpoint that does no domain
# work, and reports where p99 first breaches the budget. The result is the
# machine's HTTP ceiling - an upper bound NFR-1 and NFR-2 can never exceed.
#
# Three rules learned by getting this wrong, in order of how badly:
#
# 1. WARMUP IS NOT OPTIONAL. The first attempt reported 6.5 rps at 4.3 s average
#    against a target of 20 rps, which reads as catastrophic hardware. It was JIT
#    compilation: the same endpoint sustained 50 rps at 6.7 ms p95 thirty seconds
#    later. Publishing the cold number would have been a fabricated finding.
#
# 2. A STEP THAT DROPS ITERATIONS IS VOID, NOT ANNOTATED. If k6 cannot issue
#    requests fast enough, the step measured the LOAD GENERATOR, not the system.
#    The first version printed such steps with a warning and then computed a
#    "ceiling" from them - reporting a breach at 2000 rps from a step that
#    achieved 13. That is SDD 19.5's rule (an invalid run produces no report at
#    all) one layer down, and it applies here for the same reason.
#
# 3. DO NOT RAMP PAST THE CEILING. Driving 4000 rps at a stack sized for a
#    laptop left nginx unhealthy and /actuator/health taking 139 SECONDS
#    sequentially for minutes afterwards. Nothing is learned above the knee, and
#    the damage contaminates every subsequent measurement in the same run.
#
# Usage:  ./loadtest/calibration/run-calibration.sh [base-url]
set -uo pipefail

BASE="${1:-http://localhost:8080}"
K6="${K6:-k6}"
HERE="$(cd "$(dirname "$0")" && pwd)"

# p99 budget for a no-domain-work endpoint. NFR-5 allows 50 ms p99 for `search`,
# which does real work - so at or above that here, the HTTP path alone has eaten
# the entire budget of a real endpoint.
BUDGET_MS=50

# Deliberately stops near the expected knee. See rule 3.
RATES="${RATES:-100 250 500 750 1000}"

command -v "$K6" >/dev/null 2>&1 || {
  echo "k6 not on PATH. Set K6=/path/to/k6, or install: winget install GrafanaLabs.k6" >&2
  exit 1
}

run_step() {  # url rate duration -> JSON on stdout
  TARGET="$1" RATE="$2" DURATION="$3" \
    "$K6" run --quiet "$HERE/http-ceiling.js" 2>/dev/null | tail -1
}

field() {  # json key
  printf '%s' "$1" | python -c "import json,sys; print(json.load(sys.stdin).get('$2', 0))"
}

cooldown() {  # seconds
  # Idle at a trivial rate rather than sleeping: lets connection pools drain and
  # TIME_WAIT sockets clear, while keeping the JIT-compiled path warm so the next
  # step does not re-pay rule 1.
  printf '  settling (%ss)... ' "$1"
  run_step "$BASE/actuator/health/liveness" 5 "${1}s" >/dev/null
  echo 'done'
}

measure_endpoint() {
  local name="$1" path="$2"
  local url="$BASE$path"

  echo
  echo "### $name  ($path)"
  echo

  printf '  warming up (30s at 200 rps, discarded)... '
  run_step "$url" 200 30s >/dev/null
  echo 'done'
  echo

  printf '  %10s  %12s  %9s  %9s  %9s  %s\n' \
    "req rps" "achieved" "p50 ms" "p95 ms" "p99 ms" "verdict"
  printf '  %10s  %12s  %9s  %9s  %9s  %s\n' \
    "----------" "------------" "---------" "---------" "---------" "-------"

  local ceiling="" last_good=""

  for rate in $RATES; do
    local json dropped achieved p50 p95 p99
    json="$(run_step "$url" "$rate" 20s)"
    [ -z "$json" ] && { echo "  no result at ${rate} rps; stopping" >&2; break; }

    dropped="$(field "$json" dropped_iterations)"
    achieved="$(field "$json" achieved_rps)"
    p50="$(field "$json" p50_ms)"
    p95="$(field "$json" p95_ms)"
    p99="$(field "$json" p99_ms)"

    # Rule 2: void, not annotated.
    if [ "${dropped%.*}" -gt 0 ] 2>/dev/null; then
      printf '  %10s  %12s  %9s  %9s  %9s  VOID (k6 dropped %s)\n' \
        "$rate" "-" "-" "-" "-" "${dropped%.*}"
      echo "  -> the load generator, not the system, was the bottleneck. Stopping."
      break
    fi

    local verdict="ok"
    if python -c "import sys; sys.exit(0 if $p99 >= $BUDGET_MS else 1)"; then
      verdict="p99 over ${BUDGET_MS}ms"
      [ -z "$ceiling" ] && ceiling="$rate"
    else
      last_good="$rate"
    fi

    printf '  %10s  %12.1f  %9.2f  %9.2f  %9.2f  %s\n' \
      "$rate" "$achieved" "$p50" "$p95" "$p99" "$verdict"

    # Rule 3: nothing is learned above the knee, and pushing past it damages the
    # stack for whatever runs next.
    if [ -n "$ceiling" ]; then
      echo "  -> knee found; not ramping further."
      break
    fi

    cooldown 10
  done

  echo
  if [ -n "$ceiling" ]; then
    echo "  CEILING: sustained ${last_good:-<none>} rps within ${BUDGET_MS} ms p99;"
    echo "           breached at ${ceiling} rps."
  else
    echo "  CEILING: at least ${last_good:-unknown} rps within ${BUDGET_MS} ms p99"
    echo "           (no breach across the tested range)."
  fi
}

echo "================================================================"
echo " AC-0.7 part 2 - HTTP ceiling calibration"
echo "================================================================"
echo " base url : $BASE"
echo " rates    : $RATES"
echo " budget   : p99 < ${BUDGET_MS} ms"
echo " k6       : $("$K6" version | head -1)"
echo
echo " This is an UPPER BOUND, not NFR-1 or NFR-2. Those are set by AC-1.13"
echo " against search and hold, and will be a fraction of these numbers."

# Pure HTTP path: app state only, no I/O.
measure_endpoint "HTTP path only" "/actuator/health/liveness"

# Long settle before the second endpoint. Without it the second measurement
# inherits the first's exhausted connection pools and reports the recovery, not
# the endpoint.
echo
cooldown 30

# One backend round trip: also checks Postgres, Redis, disk and SSL. The gap
# between the two is the cost of touching a backend at all.
measure_endpoint "HTTP path + backend checks" "/actuator/health"

echo
echo "================================================================"
echo " Record in docs/benchmarks/000-calibration.md with the NFR-12"
echo " metadata block: hardware, JDK build, container limits, and the"
echo " fact that k6 ran co-located (NFR-13)."
echo "================================================================"
