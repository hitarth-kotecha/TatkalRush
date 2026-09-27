#!/usr/bin/env bash
# C3 (§19.2): Toxiproxy freezes Postgres for FREEZE_SECONDS during P2.
#
# REQUIRES the stack started with the chaos overlay (compose.chaos.yaml),
# which is what routes the app's Postgres connection through toxiproxy in the
# first place - see that file for why it is an overlay and not a change to
# the default stack:
#
#   docker compose -f compose.yaml -f loadtest/chaos/compose.chaos.yaml up -d --wait
#
# Expectation (§19.2): writes fail (mapped to 503 - see the failure matrix,
# §13.1); search and availability keep serving from Redis (FR-13's cache, which
# never touches Postgres on the read path); no data corruption; recovery once
# the freeze lifts.
#
# THE MECHANISM: `toxiproxy-cli toggle postgres` disables the proxy, which
# closes every connection currently passing through it and refuses new ones,
# then re-enables it after FREEZE_SECONDS. This is not a "hang" toxic (a
# `timeout` toxic that holds connections open silently) - a hard, immediate cut
# is closer to what "5 s Postgres freeze" and the expectation's own wording
# ("recovery on release") describe, and it is the toxiproxy primitive whose
# behaviour is easiest to reason about without a live run to check it against.
#
# Usage:
#   ./loadtest/chaos/c3-postgres-freeze.sh [base-url]
#
# Env:
#   FREEZE_SECONDS   how long the proxy stays disabled (default 5, per §19.2)
#   RATE, DURATION_MINUTES   same meaning as p2-sustained-mixed.js.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BASE="${1:-http://localhost:8080}"

FREEZE_SECONDS="${FREEZE_SECONDS:-5}"
RATE="${RATE:-200}"
DURATION_MINUTES="${DURATION_MINUTES:-5}"

DC="docker compose --project-directory '$ROOT' -f '$ROOT/compose.yaml'"

# Preflight: without the overlay, "toggle postgres" fails against a proxy that
# was never created, and the app is talking to Postgres directly regardless -
# a scenario that would silently test nothing rather than fail loudly. Checked
# with plain eval, not exported, because this runs once, up front, not as the
# backgrounded CHAOS_CMD.
if ! eval "$DC exec -T toxiproxy /toxiproxy-cli list" 2>/dev/null | grep -q '^postgres\b'; then
  echo "C3 requires the chaos overlay - the 'postgres' proxy does not exist on toxiproxy." >&2
  echo "Start the stack with:" >&2
  echo "  docker compose -f compose.yaml -f loadtest/chaos/compose.chaos.yaml up -d --wait" >&2
  exit 2
fi

# Mid-run of the (possibly fractional) profile duration - see
# c2-redis-flushall.sh for why this uses python rather than bash arithmetic.
FREEZE_AT_SECONDS="$(python -c "print(int($DURATION_MINUTES * 60 / 2))")"

export RATE DURATION_MINUTES
export CHAOS_SCENARIO="C3"
export CHAOS_DELAY_SECONDS="$FREEZE_AT_SECONDS"
export CHAOS_CMD="$DC exec -T toxiproxy /toxiproxy-cli toggle postgres && \
  sleep $FREEZE_SECONDS && \
  $DC exec -T toxiproxy /toxiproxy-cli toggle postgres"

echo "C3: freezing Postgres for ${FREEZE_SECONDS}s at ${FREEZE_AT_SECONDS}s into a ${DURATION_MINUTES}min P2 run"
echo

"$HERE/../run-profile.sh" p2 "$BASE"
STATUS=$?

echo
echo "C3's own reminder: search_ok should keep climbing through the freeze window"
echo "(FR-13's Redis cache never touches Postgres); holds_ok should flatten and"
echo "failures/RETRY_LATER should cluster in that same window, not spread across"
echo "the whole run. The result JSON does not carry a per-second timeline to"
echo "assert this automatically - check Grafana's window for this run, or add"
echo "k6's own --out for a timestamped series if this needs to be unattended."

exit $STATUS
