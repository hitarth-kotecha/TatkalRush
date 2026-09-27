#!/usr/bin/env bash
# C4 (§19.2): Kafka broker restart during P1.
#
# Expectation: Strategy B buffers and retries rather than dropping commands -
# see DD-050: nothing in KafkaSeatAllocator actively buffers anything, because
# Kafka's own producer/consumer retry the broker connection on its own, and a
# rebalance (if the restart is slow enough to trigger one) blocks delivery the
# same way a replica kill does. No double-apply (INV-1); checkpoints survive
# because they live in Postgres (DD-013), which this scenario never touches.
#
# Strategy A has no meaningful expectation here - it does not use Kafka at all
# in the write path (§9.2). Running C4 under Strategy A mostly tests that the
# stack is architected the way §8.1 claims: killing a service nothing on the
# write path depends on should be a non-event.
#
# compose.yaml's own comment on the kafka service names this exact scenario:
# "replication.factor=1 with min.insync.replicas=1 is a DELIBERATE single
# point of failure... Chaos scenario C4 restarts this broker precisely to show
# what the system does when that SPOF blinks." Unlike app-1/app-2, the kafka
# service carries no restart policy of its own, so `docker compose restart`
# (not kill) is what actually brings it back - a kill would leave it down.
#
# Usage:
#   ./loadtest/chaos/c4-kafka-restart.sh [base-url]
#
# Env:
#   PEAK_RPS, RAMP_SECONDS, HOLD_SECONDS   same meaning as p1-tatkal-spike.js.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BASE="${1:-http://localhost:8080}"

PEAK_RPS="${PEAK_RPS:-60}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
HOLD_SECONDS="${HOLD_SECONDS:-30}"

# Same reasoning as C1's KILL_AT_SECONDS: mid-hold, not the exact ramp-complete
# instant, to tolerate k6's setup() taking longer than the ramp at high VU
# counts.
RESTART_AT_SECONDS="${RESTART_AT_SECONDS:-$((RAMP_SECONDS + HOLD_SECONDS / 2))}"

export PEAK_RPS RAMP_SECONDS HOLD_SECONDS
export CHAOS_SCENARIO="C4"
export CHAOS_DELAY_SECONDS="$RESTART_AT_SECONDS"
export CHAOS_CMD="docker compose --project-directory '$ROOT' -f '$ROOT/compose.yaml' restart kafka"

echo "C4: restarting kafka ${RESTART_AT_SECONDS}s into the real run (${RAMP_SECONDS}s ramp + half of ${HOLD_SECONDS}s hold)"
echo "    kafka has no restart: policy of its own - this uses 'compose restart', not kill,"
echo "    or it would stay down for the rest of the run"
echo

"$HERE/../run-profile.sh" p1 "$BASE"
STATUS=$?

echo
echo "C4's own reminder: under Strategy B this restart may also trigger a"
echo "consumer-group rebalance if the broker is unreachable long enough, in"
echo "which case C1's reminders about dedup_evictions and NFR-8 apply here too."

exit $STATUS
