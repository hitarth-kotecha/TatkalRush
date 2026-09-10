#!/usr/bin/env bash
# Writes loadtest/targets.json from the running database.
#
# WHY THIS EXISTS, rather than a station pair typed into a k6 script.
#
# The seed generator picks each train's route by sampling 8-25 stations at random
# from a roster of 30, under a fixed PRNG seed (FR-48, FR-50). That makes the
# routes reproducible but not predictable: nobody can write "NDLS to BCT" and know
# a train runs it. Worse, a hard-coded pair keeps compiling and keeps returning
# HTTP 200 with an empty train list the day trainCount or the roster changes - a
# load profile targeting nothing, at full speed, looking healthy.
#
# So the targets come from the system itself, once, before the run.
#
# Usage:  ./loadtest/extract-targets.sh [journey-date-from] [journey-date-to]
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/targets.json"

# Defaults cover the seeded window's later half, which is where the Tatkal
# windows are open under a P40D clock offset (see docs/runbook.md).
FROM="${1:-2026-10-21}"
TO="${2:-2026-10-30}"

PSQL=(docker compose exec -T postgres psql -U tatkal -d tatkal -tAc)

# One row per (schedule, class, quota), with the FULL route: seq 0 to max(seq).
#
# The full route is deliberate on both endpoints. For search it is the most work
# FR-13 can be asked for - the minimum free count over every segment. For hold it
# is the hardest allocation, needing one berth free across the whole train. A
# two-segment leg would measure a much easier question and report it as the
# system's throughput.
read -r -d '' SQL <<'ENDSQL'
SELECT coalesce(json_agg(t ORDER BY t.schedule_id, t.travel_class, t.quota_type), '[]')
FROM (
  SELECT s.id                        AS schedule_id,
         tr.number                   AS train_number,
         tr.is_hot                   AS hot,
         to_char(s.journey_date, 'YYYY-MM-DD') AS journey_date,
         origin.code                 AS from_code,
         terminus.code               AS to_code,
         first_stop.seq              AS from_seq,
         last_stop.seq               AS to_seq,
         q.travel_class,
         q.quota_type,
         count(pb.berth_id)::int     AS berths
  FROM schedules s
  JOIN trains tr        ON tr.id = s.train_id
  JOIN quota_pools q    ON q.schedule_id = s.id
  JOIN pool_berths pb   ON pb.pool_id = q.id
  JOIN train_stops first_stop
    ON first_stop.train_id = tr.id AND first_stop.seq = 0
  JOIN stations origin  ON origin.id = first_stop.station_id
  JOIN train_stops last_stop
    ON last_stop.train_id = tr.id
   AND last_stop.seq = (SELECT max(seq) FROM train_stops m WHERE m.train_id = tr.id)
  JOIN stations terminus ON terminus.id = last_stop.station_id
  WHERE s.status = 'OPEN'
    AND s.journey_date BETWEEN DATE 'FROM_DATE' AND DATE 'TO_DATE'
  GROUP BY s.id, tr.number, tr.is_hot, s.journey_date,
           origin.code, terminus.code, first_stop.seq, last_stop.seq,
           q.travel_class, q.quota_type
) t
ENDSQL

SQL="${SQL//FROM_DATE/$FROM}"
SQL="${SQL//TO_DATE/$TO}"

echo "Extracting targets for journey dates $FROM .. $TO"

# Via a file, not a shell variable. The seeded window yields several hundred pools
# and the JSON runs past what an argv can carry - which surfaced as "Argument list
# too long" attributed to python, an error that reads like a Python problem and is
# not one.
RAW="$(mktemp)"
trap 'rm -f "$RAW"' EXIT
"${PSQL[@]}" "$SQL" | tr -d '\r' > "$RAW"

if [ ! -s "$RAW" ] || [ "$(cat "$RAW")" = "[]" ]; then
  echo "No targets found. Is the database seeded, and are those dates in range?" >&2
  exit 1
fi

# §19.1: one k6 virtual user maps to one distinct synthetic user, on every
# profile. The count is emitted rather than assumed, because §19.5 voids any run
# where FR-60's per-user cap bound - and an under-provisioned user table is the
# only way that happens.
USER_COUNT="$("${PSQL[@]}" "SELECT count(*) FROM users" | tr -d '\r')"
MIN_USER="$("${PSQL[@]}" "SELECT min(id) FROM users" | tr -d '\r')"

python - "$OUT" "$RAW" "$USER_COUNT" "$MIN_USER" "$FROM" "$TO" <<'ENDPY'
import json, sys, datetime

out, raw_path, user_count, min_user, date_from, date_to = sys.argv[1:7]
with open(raw_path, encoding="utf-8") as f:
    rows = json.load(f)

doc = {
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "journeyDates": {"from": date_from, "to": date_to},
    "users": {"firstId": int(min_user), "count": int(user_count)},
    "targets": rows,
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(doc, f, indent=1)

hot = sum(1 for r in rows if r["hot"])
print("  %d targets (%d on hot trains), %d users from id %s"
      % (len(rows), hot, int(user_count), min_user))
print("  berths across targets: %d" % sum(r["berths"] for r in rows))
ENDPY

echo "Wrote $OUT"
