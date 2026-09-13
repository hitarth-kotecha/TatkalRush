#!/usr/bin/env bash
# Runs one §19 profile end to end and applies §19.5's validity gate.
#
# A profile run is not "start k6, read the numbers". AC-1.2 and AC-1.3 both gate
# on INVARIANTS, and §19.5 makes a run with a single RATE_LIMITED invalid rather
# than merely worse. So the sequence is fixed:
#
#   0. ask the running system for its TTL and clock offset, and check routing
#   1. reset inventory      - a profile that starts on a depleted pool measures
#                             the sold-out path
#   2. settle               - the reset rewrites 3,600 pools through Lua and
#                             truncates a table Postgres then autovacuums
#   3. run the profile
#   4. QUIESCE              - INV-5, INV-8 and INV-12 compare Redis against
#                             Postgres, and a live hold makes them legitimately
#                             disagree. Checking under load reports violations
#                             that are not violations.
#                             A CONDITION, not a sleep: wait until §13.2's reaper
#                             has emptied every holds: key and expired every HELD
#                             booking. How long that took is itself reported.
#   5. check §14
#   6. apply §19.5 and say VALID or INVALID, with the reason
#
# Usage:
#   ./loadtest/run-profile.sh p1 [base-url]
#   ./loadtest/run-profile.sh p2 [base-url]
set -uo pipefail

PROFILE="${1:-}"
BASE="${2:-http://localhost:8080}"
K6="${K6:-k6}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
RUN_ID="$(date +%s)"
RESULTS="$HERE/results"

case "$PROFILE" in
  p1) SCRIPT="$HERE/profiles/p1-tatkal-spike.js" ;;
  p2) SCRIPT="$HERE/profiles/p2-sustained-mixed.js" ;;
  *)  echo "usage: $0 {p1|p2} [base-url]" >&2; exit 2 ;;
esac

REDIS_PORT="${TATKAL_REDIS_PORT:-6379}"   # compose.yaml: Windows can reserve 6379
SETTLE_SECONDS="${SETTLE_SECONDS:-15}"

# The TTL and the clock offset come from the RUNNING SYSTEM, not from this
# script's environment. Both are inputs to the checks: k6 sizes its users from the
# TTL (FR-20), and INV-5 judges hold expiries on the system's clock. A script that
# trusted its own copy ran INV-5 on the host clock against a P40D stack, and the
# check passed having examined nothing.
APP_ENV="$(docker compose exec -T app-1 sh -c 'echo "$TATKALRUSH_HOLD_TTLMS $TATKALRUSH_CLOCK_OFFSET"' 2>/dev/null | tr -d '\r')"
read -r APP_TTL_MS APP_CLOCK_OFFSET <<<"$APP_ENV"
[ -n "${APP_TTL_MS:-}" ] || { echo "cannot read TATKALRUSH_HOLD_TTLMS from app-1 - is the stack up?" >&2; exit 1; }
APP_CLOCK_OFFSET="${APP_CLOCK_OFFSET:-PT0S}"
HOLD_TTL_SECONDS=$((APP_TTL_MS / 1000))

# §13.2's reaper sweeps every 5 s, so a drained system is at most TTL + one sweep
# away. The margin is for a sweep that lands just as the last hold expires, and
# for the lease changing hands; past it, the reaper is not keeping up.
QUIESCE_TIMEOUT_SECONDS="${QUIESCE_TIMEOUT_SECONDS:-$((HOLD_TTL_SECONDS + 60))}"

mkdir -p "$RESULTS"

[ -f "$ROOT/loadtest/targets.json" ] || {
  echo "loadtest/targets.json missing. Run ./loadtest/extract-targets.sh first." >&2
  exit 1
}
for jar in ops/pool-warmup/target/pool-warmup.jar ops/invariant-checker/target/invariant-checker.jar; do
  [ -f "$ROOT/$jar" ] || { echo "$jar missing. Run: mvn -o package -DskipTests" >&2; exit 1; }
done

PSQL=(docker compose exec -T postgres psql -U tatkal -d tatkal)

# shellcheck source=lib/preflight.sh
. "$HERE/lib/preflight.sh"

echo "================================================================"
echo " ${PROFILE^^} - $(basename "$SCRIPT")"
echo "================================================================"
echo " base url        : $BASE"
echo " hold TTL        : ${HOLD_TTL_SECONDS}s  (from app-1; DD-044 shortens it for benchmark runs)"
echo " clock offset    : ${APP_CLOCK_OFFSET}  (from app-1; FR-31)"
echo " quiesce         : until drained, at most ${QUIESCE_TIMEOUT_SECONDS}s"
echo " run id          : $RUN_ID"

# The Tatkal window's open date, computed by the SYSTEM rather than by this
# script. FR-28's rule is IST calendar arithmetic and reimplementing it in bash
# would be a second definition that drifts; asking the app which pools it reports
# as bookable is asking the thing that decides.
if [ "$PROFILE" = "p1" ]; then
  printf ' open Tatkal date: '
  TATKAL_DATE="$("${PSQL[@]}" -tAc "
    SELECT to_char(min(s.journey_date), 'YYYY-MM-DD')
    FROM schedules s
    JOIN quota_pools q ON q.schedule_id = s.id AND q.quota_type = 'TATKAL'
    WHERE s.status = 'OPEN'
      AND s.journey_date >= DATE '2026-10-21'" | tr -d '\r')"
  export TATKAL_DATE
  echo "$TATKAL_DATE"
fi

echo
printf ' routing preflight... '
routing_preflight "$BASE" "$ROOT" || exit 1
echo "ok"

# Rule 1 from AC-0.7 and AC-1.13, which this driver did not enforce until it cost
# a run. The first P1 after the replicas restarted reported hold p50 2,146 ms and
# 161 failures; the identical run a few minutes later reported p50 8.4 ms and none.
# A cold JVM interprets the hot path while C1 and C2 compile it on the same cores
# the load is using, and on this box that turned into multi-second GC pauses and a
# drained connection pool. The first seconds after a restart measure the compiler.
#
# BEFORE the reset, on purpose: warm-up holds consume inventory, and the reset
# puts it all back. The same profile script runs, so the same code is warmed.
WARMUP_SECONDS="${WARMUP_SECONDS:-30}"
printf ' warming up (%ss, discarded)... ' "$WARMUP_SECONDS"
RUN_ID="warmup-$RUN_ID" BASE_URL="$BASE" HOLD_TTL_SECONDS="$HOLD_TTL_SECONDS" \
  PEAK_RPS=20 RATE=100 DURATION_MINUTES="$(python -c "print($WARMUP_SECONDS/60)")" \
  RAMP_SECONDS=5 HOLD_SECONDS="$((WARMUP_SECONDS - 10))" \
  "$K6" run --quiet "$SCRIPT" >/dev/null 2>&1
echo "done"

printf ' resetting inventory... '
"${PSQL[@]}" -q -c "TRUNCATE ledger_entries, refunds, payment_events, payments,
  seat_allocations, passengers, bookings RESTART IDENTITY CASCADE" >/dev/null 2>&1
WARMUP_LOG="$(java -jar "$ROOT/ops/pool-warmup/target/pool-warmup.jar" \
  "jdbc:postgresql://localhost:5432/tatkal" tatkal tatkal localhost "$REDIS_PORT" 2>&1)" || {
  # Not swallowed. A warm-up that failed leaves the LAST run's masks in Redis,
  # and a spike against depleted pools measures SEAT_UNAVAILABLE and looks fine.
  echo "FAILED"; echo "$WARMUP_LOG" | tail -5 >&2; exit 1
}
echo "done"

printf ' settling (%ss)... ' "$SETTLE_SECONDS"
sleep "$SETTLE_SECONDS"
echo "done"

echo
echo " running..."
OUT_JSON="$RESULTS/${PROFILE}-${RUN_ID}.json"

# Host paging, sampled for exactly the measured window (lib/host-paging.ps1 says
# why). Windows only: on a Linux host the stack is not inside a VM the host can
# page out behind the guest's back, and the columns are reported as unavailable.
HOST_CSV="$RESULTS/${PROFILE}-${RUN_ID}-host.csv"
HOST_STOP="$RESULTS/.${PROFILE}-${RUN_ID}.stop"
HOST_SAMPLER=""
if command -v powershell.exe >/dev/null 2>&1; then
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$HERE/lib/host-paging.ps1")" \
    -Out "$(cygpath -w "$HOST_CSV")" -StopFile "$(cygpath -w "$HOST_STOP")" >/dev/null 2>&1 &
  HOST_SAMPLER=$!
fi

RUN_ID="$RUN_ID" BASE_URL="$BASE" HOLD_TTL_SECONDS="$HOLD_TTL_SECONDS" OUT="$OUT_JSON" \
  "$K6" run --quiet "$SCRIPT" 2>/dev/null | tail -1 > /dev/null

if [ -n "$HOST_SAMPLER" ]; then
  touch "$HOST_STOP"; wait "$HOST_SAMPLER" 2>/dev/null; rm -f "$HOST_STOP"
fi

[ -s "$OUT_JSON" ] || { echo " k6 produced no result" >&2; exit 1; }

# Folded into the result JSON, so the numbers travel with the run (NFR-12).
python - "$OUT_JSON" "$HOST_CSV" <<'ENDPY'
import csv, json, os, sys
out, host = sys.argv[1], sys.argv[2]
d = json.load(open(out))
if os.path.exists(host):
    rows = [r for r in csv.DictReader(open(host, encoding="ascii", errors="ignore")) if r.get("pagesInPerSec", "").strip()]
    pin = sorted(int(r["pagesInPerSec"]) for r in rows)
    avail = [int(r["availableMB"]) for r in rows]
    if pin:
        d["host_samples"] = len(pin)
        d["host_page_ins_p50"] = pin[len(pin) // 2]
        d["host_page_ins_p90"] = pin[int(len(pin) * 0.9)]
        d["host_available_mb_min"] = min(avail)
json.dump(d, open(out, "w"), indent=2)
ENDPY

python - "$OUT_JSON" <<'ENDPY'
import json, sys
d = json.load(open(sys.argv[1]))
rows = [
    ("profile", d.get("profile")),
    ("VUs", "%d" % d.get("vus", 0)),
    ("achieved rps", "%.1f" % d.get("achieved_rps", 0)),
    ("dropped iterations", d.get("dropped_iterations", 0)),
]
if d.get("profile") == "P1":
    rows += [("hold p50/p95/p99 ms", "%.1f / %.1f / %.1f" % (d["p50_ms"], d["p95_ms"], d["p99_ms"]))]
else:
    rows += [
        ("search p50/p95/p99 ms", "%.1f / %.1f / %.1f" % (d["search_p50_ms"], d["search_p95_ms"], d["search_p99_ms"])),
        ("hold   p50/p95/p99 ms", "%.1f / %.1f / %.1f" % (d["hold_p50_ms"], d["hold_p95_ms"], d["hold_p99_ms"])),
    ]
rows += [
    ("searches ok", d.get("search_ok", 0)),
    ("holds ok", d.get("holds_ok", 0)),
    ("SEAT_UNAVAILABLE", d.get("seat_unavailable", 0)),
    ("QUOTA_LOCKED", d.get("quota_locked", 0)),
    ("TOO_MANY_HOLDS", d.get("too_many_holds", 0)),
    ("RATE_LIMITED", d.get("rate_limited", 0)),
    ("failures", d.get("failures", 0)),
]
if "host_page_ins_p50" in d:
    rows.append(("host page-ins/s p50/p90", "%d / %d" % (d["host_page_ins_p50"], d["host_page_ins_p90"])))
    rows.append(("host available MB min", d["host_available_mb_min"]))
else:
    rows.append(("host paging", "not sampled (non-Windows host)"))
for k, v in rows:
    print("   %-22s %s" % (k, v))
ENDPY

echo
# Waiting for §13.2's reaper, not for a guess. This used to be a fixed sleep plus a
# "nudge" - a short burst of holds meant to trigger LAZY reaping - written before
# anyone noticed the background reaper did not exist. The nudge could not work: it
# only touched the pools it booked from, and the holds it made were still live
# when INV-8 ran, so it manufactured the very violations it was meant to clear.
printf ' quiescing: waiting for the reaper to drain holds... '
QUIESCE_STARTED=$(date +%s)
DRAINED=""
while :; do
  REDIS_HOLDS="$(docker compose exec -T redis sh -c "redis-cli --scan --pattern 'holds:*' | wc -l" 2>/dev/null | tr -dc 0-9)"
  PG_HELD="$("${PSQL[@]}" -tAc "SELECT count(*) FROM bookings WHERE status = 'HELD'" 2>/dev/null | tr -dc 0-9)"
  WAITED=$(( $(date +%s) - QUIESCE_STARTED ))
  if [ "${REDIS_HOLDS:-x}" = "0" ] && [ "${PG_HELD:-x}" = "0" ]; then
    DRAINED="$WAITED"
    echo "drained in ${WAITED}s"
    break
  fi
  if [ "$WAITED" -ge "$QUIESCE_TIMEOUT_SECONDS" ]; then
    echo "NOT DRAINED after ${WAITED}s (redis pools with holds: ${REDIS_HOLDS:-?}, HELD bookings: ${PG_HELD:-?})"
    break
  fi
  sleep 2
done

echo
echo " §14 invariants (QUIESCED):"
echo
java -Dstdout.encoding=UTF-8 \
  -Dtatkal.hold.ttl-ms="$APP_TTL_MS" -Dtatkal.clock.offset="$APP_CLOCK_OFFSET" \
  -jar "$ROOT/ops/invariant-checker/target/invariant-checker.jar" \
  "jdbc:postgresql://localhost:5432/tatkal" tatkal tatkal localhost "$REDIS_PORT" \
  | sed 's/^/   /'
INVARIANTS_OK=${PIPESTATUS[0]}

# NFR-9. Any trip of no_overlapping_allocations means an allocator bug shipped
# and money was already taken; it is a boolean stored as a number, not a trend.
CONSTRAINT_VIOLATIONS="$(curl -s "$BASE/actuator/prometheus" \
  | grep -E '^allocation_constraint_violations_total' | awk '{print $2}' | head -1)"
CONSTRAINT_VIOLATIONS="${CONSTRAINT_VIOLATIONS:-0}"

echo
echo "================================================================"
python - "$OUT_JSON" "$INVARIANTS_OK" "$CONSTRAINT_VIOLATIONS" "$DRAINED" "$QUIESCE_TIMEOUT_SECONDS" <<'ENDPY'
import json, sys
d = json.load(open(sys.argv[1]))
invariants_ok = sys.argv[2] == "0"
constraints = float(sys.argv[3] or 0)
drained = sys.argv[4]
quiesce_timeout = sys.argv[5]

reasons = []
# §19.5, in the order the SDD states it.
if d.get("rate_limited", 0) > 0:
    reasons.append(
        "rate_limited_total = %d. §19.5: this reflects HARNESS configuration, not "
        "system state - requests were refused at the edge before reaching the "
        "system, so any throughput here describes load that was never served."
        % d["rate_limited"])
if d.get("too_many_holds", 0) > 0:
    # Not named in §19.5, and the same defect as RATE_LIMITED one rule over: FR-20
    # refused requests because the harness gave too few users too many holds.
    # The load was shaped by the harness's user count, not offered to the system.
    reasons.append(
        "TOO_MANY_HOLDS = %d. FR-20 refused holds because the harness under-provisioned "
        "users for this rate and TTL - the same class of defect as RATE_LIMITED"
        % d["too_many_holds"])
if constraints > 0:
    reasons.append("allocation_constraint_violations_total = %g (NFR-9)" % constraints)
if not drained:
    reasons.append(
        "the system did not drain within %ss - §13.2's reaper is not keeping up (or not "
        "running), so the quiesced checks below were asked about a live system"
        % quiesce_timeout)
if not invariants_ok:
    reasons.append("§14 invariant violations - see above")
# Not in §19.5, but the same class of defect one layer down: a step that dropped
# iterations measured the load generator.
if d.get("dropped_iterations", 0) > 0:
    reasons.append(
        "k6 dropped %d iterations - the load generator was the bottleneck, so this "
        "run measures the harness" % d["dropped_iterations"])
if d.get("failures", 0) > 0:
    reasons.append("%d request failures (5xx, timeouts, or unclassified codes)"
                   % d["failures"])
# The laptop's pagefile, not the system. See lib/host-paging.ps1.
#
# Measured on the reference laptop (7.9 GB): idle host 0-135 page-ins/s; P1 at
# 100 rps sustaining 1,300-1,900 while replica GC pauses reached 16 s and 1,249
# requests failed. And the case that decided the threshold: P1 at 60 rps with p90
# 1,314 and 700 MB available met EVERY latency budget - 0 failures, p99 115 ms
# against NFR-4's 800 - and was voided by this rule. Correctly: the same run with
# more host headroom earlier the same day had p99 30.5 ms. Paging had already made
# the tail 3.8x worse without breaking anything, which is exactly when a guard has
# to fire, because a report cannot tell 115 ms of system from 115 ms of pagefile.
# p90, not max, so a single antivirus scan does not void a run.
HOST_PAGING_P90_LIMIT = 1000
if d.get("host_page_ins_p90", 0) > HOST_PAGING_P90_LIMIT:
    reasons.append(
        "the HOST was paging: %d hard page-ins/s at p90 (limit %d), minimum %d MB "
        "available. Docker's VM was being read back from the Windows pagefile, so "
        "latency here is disk I/O on the laptop - free host RAM and re-run"
        % (d["host_page_ins_p90"], HOST_PAGING_P90_LIMIT, d.get("host_available_mb_min", -1)))

if reasons:
    print(" RUN IS INVALID (§19.5). No report may be generated from it.")
    for r in reasons:
        print("   - %s" % r)
    sys.exit(1)

print(" RUN IS VALID (§19.5): no RATE_LIMITED, no constraint violations,")
print(" every §14 invariant passed, no dropped iterations, no failures.")
ENDPY
VERDICT=$?
echo "================================================================"
echo " result JSON: $OUT_JSON"
exit $VERDICT
