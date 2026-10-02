#!/usr/bin/env bash
# C1 (§19.2): docker kill one app replica at P1's spike peak.
#
# Expectation (§19.2, AC-2.4, AC-2.6):
#   Strategy B  partition ownership rebalances onto the survivor. NFR-8's <=2s
#               warm-recovery budget is the target.
#   Strategy A  near-zero impact - it has no partition ownership to lose;
#               every allocation is one atomic Lua call inside Redis, and the
#               dead replica's in-flight requests just fail over to the other
#               one at nginx.
#   Both        zero lost confirmed bookings, zero allocation_constraint_violations_total
#               (INV-1, INV-2 - checked below via run-profile.sh's own §14 pass).
#
# restart: unless-stopped (compose.yaml) brings the killed replica back on its
# own during the run, which is deliberate: a real crash-and-recover, not a
# permanent removal, is what C1 is testing. The survivor's takeover of the
# dead replica's partitions is the event under test; the dead one rejoining
# later and discarding its own state (DD-007) is a second, smaller version of
# the same event, not a confound.
#
# WHAT THIS SCRIPT CANNOT CHECK, AND WHY - stated here rather than silently
# skipped (NFR-14):
#   - dedup_evictions_before_window_expiry_total == 0 (AC-2.6). KafkaSeatAllocator
#     tracks this as a plain in-JVM counter (DedupCache.prematureEvictions);
#     nothing wires it to a MeterRegistry, so /actuator/prometheus does not
#     expose it. There is nothing outside the JVM to read it from yet.
#   - NFR-8's <=2s bound, precisely. partition_replay_duration_seconds now
#     exists (a Timer around every replayPartition() call, tagged by
#     partition) and is scraped via /actuator/prometheus like any other
#     metric - but DD-049 found replay always starts from offset 0, never from
#     the checkpoint it just wrote, and wiring the metric does not change that.
#     Expect the number to exceed the budget; this script reports it, it does
#     not assert against it. RETRY_LATER's count (lib/api.js) is a secondary
#     signal: it should stop shortly after the kill under Strategy B, and
#     near-zero throughout under Strategy A.
#
# Usage:
#   ./loadtest/chaos/c1-kill-replica.sh [base-url]
#
# Env:
#   KILL_TARGET   which replica dies (default: app-1). The other one survives
#                 and, under Strategy B, takes over its partitions.
#   PEAK_RPS, RAMP_SECONDS, HOLD_SECONDS   same meaning as p1-tatkal-spike.js.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BASE="${1:-http://localhost:8080}"

PEAK_RPS="${PEAK_RPS:-60}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
HOLD_SECONDS="${HOLD_SECONDS:-30}"
KILL_TARGET="${KILL_TARGET:-app-1}"

# Lands mid-hold, not at the exact ramp-complete instant. k6's setup() (a token
# fetch per VU, lib/api.js) runs before the ramp starts and takes longer at
# higher PEAK/VU counts, so a fixed "sleep RAMP_SECONDS" timed from k6-process
# start can fire before the spike is even ramping. The whole hold phase runs
# at PEAK, so landing anywhere inside it is still "at spike peak", and this
# tolerates a slow setup() without needing to synchronise on it precisely.
KILL_AT_SECONDS="${KILL_AT_SECONDS:-$((RAMP_SECONDS + HOLD_SECONDS / 2))}"

export PEAK_RPS RAMP_SECONDS HOLD_SECONDS
export CHAOS_SCENARIO="C1"
export CHAOS_DELAY_SECONDS="$KILL_AT_SECONDS"
export CHAOS_CMD="docker compose --project-directory '$ROOT' -f '$ROOT/compose.yaml' kill '$KILL_TARGET'"

echo "C1: killing $KILL_TARGET ${KILL_AT_SECONDS}s into the real run (${RAMP_SECONDS}s ramp + half of ${HOLD_SECONDS}s hold)"
echo

"$HERE/../run-profile.sh" p1 "$BASE"
STATUS=$?

echo
echo "C1's own reminders (not checked above - see this file's header):"
echo "  - dedup_evictions_before_window_expiry_total is not exposed via Prometheus yet"
echo "  - partition_replay_duration_seconds IS exposed now (query /actuator/prometheus"
echo "    on whichever replica survived) - but per DD-049, expect it to exceed NFR-8's"
echo "    2s budget: replay still starts from offset 0, not from a checkpoint"

exit $STATUS
