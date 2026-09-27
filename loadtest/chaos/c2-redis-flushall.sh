#!/usr/bin/env bash
# C2 (§19.2): redis-cli FLUSHALL mid-run, during P2.
#
# Expectation: in-flight holds are lost (they only ever lived in Redis);
# confirmed bookings are intact (they are rows in Postgres, untouched by
# FLUSHALL); masks AND free counts are rebuilt; INV-8 and INV-12 pass after the
# rebuild; FR-24's late-payment decisions are unaffected because they read
# `bookings.hold_expires_at` in Postgres, never Redis (§9.3, restated in
# p2-sustained-mixed.js's own header).
#
# THE REBUILD IS NOT AUTOMATIC (DD-040: "§13.4's rebuild is an explicit
# operation, not lazy initialisation"). Nothing in the app notices an empty
# Redis and repopulates it - the first hold after a real FLUSHALL would fail
# with "pool not provisioned" forever, not self-heal. So this script pairs the
# flush with the same rebuild tool run-profile.sh's own reset step uses
# (`ops/pool-warmup`'s PoolWarmupMain), run WITHOUT truncating anything first -
# unlike that reset step, which starts from an empty database. Run against a
# live, mid-P2 Postgres, PoolWarmupMain reads current `quota_pools`,
# `pool_berths` and CONFIRMED `seat_allocations` and writes exactly the masks
# and free counts those confirmed bookings imply - which is the rebuild §13.4
# and this scenario's expectation both describe.
#
# The gap between FLUSHALL and the rebuild completing is itself part of what
# C2 measures: every hold or search that lands in it should see a clean,
# classified failure (SEAT_UNAVAILABLE at worst, not corruption), never a wrong
# answer.
#
# Usage:
#   ./loadtest/chaos/c2-redis-flushall.sh [base-url]
#
# Env:
#   RATE, DURATION_MINUTES, WRITE_FRACTION   same meaning as p2-sustained-mixed.js.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BASE="${1:-http://localhost:8080}"

RATE="${RATE:-200}"
DURATION_MINUTES="${DURATION_MINUTES:-5}"
REDIS_PORT="${TATKAL_REDIS_PORT:-6379}"

# Mid-run of the (possibly fractional) profile duration, matching how
# run-profile.sh itself does duration arithmetic (its own warm-up step: `python
# -c "print($WARMUP_SECONDS/60)"`) rather than bash's integer-only arithmetic.
FLUSH_AT_SECONDS="$(python -c "print(int($DURATION_MINUTES * 60 / 2))")"

for jar in ops/pool-warmup/target/pool-warmup.jar; do
  [ -f "$ROOT/$jar" ] || { echo "$jar missing. Run: mvn -o package -DskipTests" >&2; exit 1; }
done

export RATE DURATION_MINUTES
export CHAOS_SCENARIO="C2"
export CHAOS_DELAY_SECONDS="$FLUSH_AT_SECONDS"
export CHAOS_CMD="docker compose --project-directory '$ROOT' -f '$ROOT/compose.yaml' exec -T redis redis-cli FLUSHALL && \
  java -jar '$ROOT/ops/pool-warmup/target/pool-warmup.jar' 'jdbc:postgresql://localhost:5432/tatkal' tatkal tatkal localhost '$REDIS_PORT'"

echo "C2: FLUSHALL + explicit rebuild (DD-040) ${FLUSH_AT_SECONDS}s into a ${DURATION_MINUTES}min P2 run"
echo

"$HERE/../run-profile.sh" p2 "$BASE"
STATUS=$?

echo
echo "C2's own reminder: run-profile.sh's §14 pass above already re-checks INV-8"
echo "and INV-12 post-run, which is §19.2's own wording for this scenario turned"
echo "into the same assertion DD-040 gives ops/pool-warmup's own test."

exit $STATUS
