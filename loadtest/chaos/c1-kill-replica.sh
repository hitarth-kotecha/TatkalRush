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
# The killed replica is started again explicitly, DOWN_SECONDS later. It does NOT
# come back on its own: compose.yaml's `restart: unless-stopped` does not apply
# to `docker kill` (Docker records it as a manual stop; found live, the container
# stayed Exited (137) for the rest of the run). A real crash-and-recover is what
# C1 is testing: the survivor's takeover of the dead replica's partitions is the
# event, and the dead one rejoining later and discarding its own state (DD-007)
# is a second, smaller version of the same event, not a confound.
#
# WHAT THIS SCRIPT CANNOT CHECK, AND WHY - stated here rather than silently
# skipped (NFR-14):
#   - dedup_evictions_before_window_expiry_total == 0 (AC-2.6). It is exposed on
#     /actuator/prometheus as a cumulative counter that survives partition
#     revocation, but nothing here scrapes and asserts it; that is a manual
#     check after a Strategy B run, on each replica.
#   - NFR-8's <=2s bound, precisely. partition_replay_duration_seconds is
#     exposed (a Timer around every replayPartition() call, tagged by
#     partition) and replay now starts from the partition's checkpoints,
#     not offset 0 (DD-054). Read the Timer on the surviving replica after
#     the run; this script reports it, it does not assert against it.
#     RETRY_LATER's count (lib/api.js) is a secondary signal: it should stop
#     shortly after the kill under Strategy B, and near-zero throughout
#     under Strategy A.
#
# Usage:
#   ./loadtest/chaos/c1-kill-replica.sh [base-url]
#
# Env:
#   KILL_TARGET   which replica dies (default: app-1). The other one survives
#                 and, under Strategy B, takes over its partitions.
#   DOWN_SECONDS  how long it stays dead before being started again (default 15).
#   PEAK_RPS, RAMP_SECONDS, HOLD_SECONDS   same meaning as p1-tatkal-spike.js.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BASE="${1:-http://localhost:8080}"

PEAK_RPS="${PEAK_RPS:-60}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
HOLD_SECONDS="${HOLD_SECONDS:-30}"
KILL_TARGET="${KILL_TARGET:-app-1}"
DOWN_SECONDS="${DOWN_SECONDS:-15}"

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
DC="docker compose --project-directory '$ROOT' -f '$ROOT/compose.yaml'"
export CHAOS_CMD="$DC kill '$KILL_TARGET' && sleep $DOWN_SECONDS && $DC start '$KILL_TARGET'"

echo "C1: killing $KILL_TARGET ${KILL_AT_SECONDS}s into the real run (${RAMP_SECONDS}s ramp + half of ${HOLD_SECONDS}s hold), restarting it ${DOWN_SECONDS}s later"
echo

"$HERE/../run-profile.sh" p1 "$BASE"
STATUS=$?

echo
echo "C1's own reminders (not checked above - see this file's header):"
echo "  - dedup_evictions_before_window_expiry_total is exposed but not asserted here:"
echo "    scrape /actuator/prometheus on both replicas after a Strategy B run - must be 0"
echo "  - partition_replay_duration_seconds is exposed (query /actuator/prometheus on"
echo "    whichever replica survived); compare it to NFR-8's 2s - replay starts from"
echo "    checkpoints now (DD-054), so this is the number that says whether that worked"

exit $STATUS
