#!/usr/bin/env bash
# AC-1.13: set NFR-1 and NFR-2 by measurement.
#
#   search, ramped until p99 breaches NFR-5's  50 ms  -> NFR-1
#   hold,   ramped until p99 breaches NFR-3's 150 ms  -> NFR-2
#
# The three rules AC-0.7 learned the hard way apply unchanged, and are enforced
# here rather than remembered:
#
#   1. WARM UP FIRST. Cold, this harness reported p99 487 ms at 20 rps - which
#      reads as catastrophic hardware and is JIT compilation. p50 in the same step
#      was 11 ms.
#   2. A STEP THAT DROPS ITERATIONS IS VOID, NOT ANNOTATED. It measured the load
#      generator, not the system.
#   3. DO NOT RAMP PAST THE KNEE. Nothing is learned above it and the damage
#      contaminates whatever runs next.
#
# One rule is new, and it is specific to `hold`:
#
#   4. HOLDS CONSUME INVENTORY. A step that exhausts the pools it targets stops
#      measuring allocation and starts measuring the SEAT_UNAVAILABLE path, which
#      is a free-count read returning zero. Inventory is therefore RESET between
#      hold steps, and the harness reports how much of each step was a real
#      allocation so the reader can check the claim.
#
# Usage:  ./loadtest/calibration/run-nfr-calibration.sh [base-url]
set -uo pipefail

BASE="${1:-http://localhost:8080}"
K6="${K6:-k6}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
RUN_ID="$(date +%s)"

STEP_DURATION="${STEP_DURATION:-30s}"
WARMUP_DURATION="${WARMUP_DURATION:-30s}"

# Bounded above by AC-0.7's 750 rps health-check ceiling: real endpoints cannot
# beat an endpoint that does no work, so ramping past it is rule 3 by another
# route.
SEARCH_RATES="${SEARCH_RATES:-25 50 100 200 350 500}"
# Hold steps are shorter than search steps, and that is arithmetic rather than
# impatience: FR-20 allows 3 active holds per user and a hold lives 120 s, so a
# step needs rate * seconds / 3 distinct users. At 30 s and 175 rps that is
# 1,750 VUs, which this box cannot ramp - it dropped 400 iterations and the step
# voided as a measurement of k6. At 20 s the same rate needs 1,167.
HOLD_RATES="${HOLD_RATES:-25 50 100 150 200}"
HOLD_STEP_DURATION="${HOLD_STEP_DURATION:-20s}"

command -v "$K6" >/dev/null 2>&1 || {
  echo "k6 not on PATH. Set K6=/path/to/k6." >&2
  exit 1
}
[ -f "$ROOT/loadtest/targets.json" ] || {
  echo "loadtest/targets.json missing. Run ./loadtest/extract-targets.sh first." >&2
  exit 1
}

field() { printf '%s' "$1" | python -c "import json,sys; print(json.load(sys.stdin).get('$2', 0))"; }

run_step() {  # endpoint rate duration -> JSON on stdout
  ENDPOINT="$1" RATE="$2" DURATION="$3" RUN_ID="$RUN_ID" BASE_URL="$BASE" \
    "$K6" run --quiet "$HERE/nfr-ramp.js" 2>/dev/null | tail -1
}

reset_inventory() {
  # Truncate first, then rebuild. The other order would rebuild from bookings
  # that are about to be deleted, leaving Redis holding berths nothing owns.
  docker compose exec -T postgres psql -U tatkal -d tatkal -q -c \
    "TRUNCATE ledger_entries, refunds, payment_events, payments, seat_allocations,
     passengers, bookings RESTART IDENTITY CASCADE" >/dev/null 2>&1
  java -jar "$ROOT/ops/pool-warmup/target/pool-warmup.jar" \
    "jdbc:postgresql://localhost:5432/tatkal" tatkal tatkal localhost 6379 >/dev/null 2>&1
}

measure() {  # endpoint budget_ms rates...
  local endpoint="$1" budget="$2"
  shift 2
  local rates="$*"

  echo
  echo "### $endpoint   (budget: p99 < ${budget} ms)"
  echo

  if [ "$endpoint" = "hold" ]; then
    printf '  resetting inventory... '
    reset_inventory
    echo 'done'
  fi

  printf '  warming up (%s at 25 rps, discarded)... ' "$WARMUP_DURATION"
  run_step "$endpoint" 25 "$WARMUP_DURATION" >/dev/null
  echo 'done'
  echo

  printf '  %8s  %10s  %8s  %8s  %8s  %8s  %s\n' \
    "req rps" "achieved" "p50 ms" "p95 ms" "p99 ms" "alloc %" "verdict"
  printf '  %8s  %10s  %8s  %8s  %8s  %8s  %s\n' \
    "--------" "----------" "--------" "--------" "--------" "--------" "-------"

  local knee="" last_good=""

  for rate in $rates; do
    if [ "$endpoint" = "hold" ]; then
      reset_inventory
    fi

    local json
    local step_duration="$STEP_DURATION"
    [ "$endpoint" = "hold" ] && step_duration="$HOLD_STEP_DURATION"
    json="$(run_step "$endpoint" "$rate" "$step_duration")"
    [ -z "$json" ] && { echo "  no result at ${rate} rps; stopping" >&2; break; }

    local dropped achieved p50 p95 p99 ok unavail failed limited
    dropped="$(field "$json" dropped_iterations)"
    achieved="$(field "$json" achieved_rps)"
    p50="$(field "$json" p50_ms)"
    p95="$(field "$json" p95_ms)"
    p99="$(field "$json" p99_ms)"
    failed="$(field "$json" failures)"
    limited="$(field "$json" rate_limited)"

    if [ "$endpoint" = "hold" ]; then
      ok="$(field "$json" holds_ok)"
      unavail="$(field "$json" seat_unavailable)"
    else
      ok="$(field "$json" search_ok)"
      unavail=0
    fi

    # Rule 2.
    if [ "${dropped%.*}" -gt 0 ] 2>/dev/null; then
      printf '  %8s  %10s  %8s  %8s  %8s  %8s  VOID (k6 dropped %s)\n' \
        "$rate" "-" "-" "-" "-" "-" "${dropped%.*}"
      echo "  -> the load generator was the bottleneck, not the system. Stopping."
      break
    fi

    # §19.5. A single rejection means load was refused at the edge before
    # reaching the system, so any throughput number from this step describes
    # requests that were never served.
    if [ "${limited%.*}" -gt 0 ] 2>/dev/null; then
      printf '  %8s  %10s  %8s  %8s  %8s  %8s  VOID (RATE_LIMITED %s)\n' \
        "$rate" "-" "-" "-" "-" "-" "${limited%.*}"
      echo "  -> §19.5: harness under-provisioned with users. Stopping."
      break
    fi

    # Rule 4, reported rather than assumed: what fraction of this step was a real
    # allocation rather than a sold-out rejection.
    local alloc
    alloc="$(python -c "
ok, un = $ok, $unavail
total = ok + un
print('%.0f%%' % (100.0 * ok / total) if total else 'n/a')
")"

    # How many DISTINCT pools the step actually touched. A profile that looks like
    # it spreads and does not is the failure §19.5 exists to prevent, one layer
    # below the rules it states - and it happened here: a SharedArray indexOf that
    # always returns -1 collapsed half of all load onto one pool, producing a wall
    # of SEAT_UNAVAILABLE and a p99 of 2.7 s that read as the system's knee.
    # It was 50.9 ms once the spread was real.
    local pools="-"
    if [ "$endpoint" = "hold" ]; then
      pools=$(docker compose exec -T postgres psql -U tatkal -d tatkal -tAc 'SELECT count(DISTINCT (schedule_id, travel_class, quota_type)) FROM bookings' 2>/dev/null | tr -dc 0-9)
    fi

    local verdict="ok"
    if python -c "import sys; sys.exit(0 if $p99 >= $budget else 1)"; then
      verdict="p99 over ${budget}ms"
      [ -z "$knee" ] && knee="$rate"
    else
      last_good="$rate"
    fi
    if [ "${failed%.*}" -gt 0 ] 2>/dev/null; then
      verdict="$verdict, ${failed%.*} FAILURES"
    fi

    printf '  %8s  %10.1f  %8.2f  %8.2f  %8.2f  %8s  %s\n' \
      "$rate" "$achieved" "$p50" "$p95" "$p99" "$alloc" "$verdict"

    # Rule 3.
    if [ -n "$knee" ]; then
      echo "  -> knee found; not ramping further."
      break
    fi
  done

  echo
  if [ -n "$knee" ]; then
    echo "  RESULT: sustained ${last_good:-<none>} rps within ${budget} ms p99;"
    echo "          breached at ${knee} rps."
  else
    echo "  RESULT: at least ${last_good:-unknown} rps within ${budget} ms p99"
    echo "          (no breach across the tested range)."
  fi
}

echo "================================================================"
echo " AC-1.13 - NFR-1 and NFR-2 from real endpoints"
echo "================================================================"
echo " base url   : $BASE"
echo " step       : $STEP_DURATION per rate, after a $WARMUP_DURATION warmup"
echo " k6         : $("$K6" version | head -1)"
echo " targets    : $(python -c "import json,sys;d=json.load(sys.stdin);print('%d pools, %d users' % (len(d['targets']), d['users']['count']))" < "$ROOT/loadtest/targets.json")"
echo
echo " AC-0.7 measured 750 rps against /actuator/health/liveness, with no domain"
echo " work in the path. These endpoints do real work and will be a FRACTION of"
echo " that. The ratio is the cost of the domain path (§9.4)."

measure "search" 50 $SEARCH_RATES
measure "hold" 150 $HOLD_RATES

echo
echo "================================================================"
echo " Record in docs/benchmarks/001-nfr-calibration.md with the full"
echo " NFR-12 metadata block, INCLUDING the clock offset the stack ran"
echo " under - a benchmark whose Tatkal windows were open is not"
echo " comparable to one whose windows were shut."
echo "================================================================"
