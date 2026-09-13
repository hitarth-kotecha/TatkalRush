# Sourced, not run. Checks shared by every driver that measures through nginx.

# routing_preflight BASE ROOT
#
# Refuses to start a run unless every request through the proxy reaches a replica
# that can serve it.
#
# This exists because the failure it catches is silent at every layer. When nginx
# held stale upstream addresses (see ops/nginx/nginx.conf), half of P1's requests
# went to psp-sim - which runs the same image without the booking routes - and came
# back 404. nginx was healthy, all three containers were healthy, nothing logged an
# error, and the run's only symptom was a failure count that looked like an
# application problem. It cost a full P1 run and an afternoon to find.
#
# API-1 search is the probe because only the booking role serves it: psp-sim
# answers 404, a dead replica 502, and a healthy replica 200. Probes are PACED so
# FR-60's per-user limit does not answer 429 and turn a routing check into a
# rate-limit check. Eight probes at round-robin reach each replica four times.
routing_preflight() {
  local base="$1" root="$2" probes="${PREFLIGHT_PROBES:-8}"
  local params user token codes bad

  # argv, not an interpolated path: Git Bash rewrites /e/... for native programs
  # only when the path is an argument. And tr -d '\r' on everything Python
  # prints, because Windows Python ends lines with CRLF - the first version of
  # this check put a carriage return into the URL, curl refused it, and a healthy
  # stack failed 8 of 8 with status 000.
  read -r user params < <(python - "$root/loadtest/targets.json" <<'ENDPY' | tr -d '\r'
import json, sys
d = json.load(open(sys.argv[1]))
t = d["targets"][0]
print(d["users"]["firstId"], "from=%s&to=%s&date=%s" % (t["from_code"], t["to_code"], t["journey_date"]))
ENDPY
)

  token="$(curl -s -m 5 -X POST -H 'Content-Type: application/json' \
    -d "{\"userId\":$user}" "$base/api/v1/auth/token" \
    | python -c "import json,sys; print(json.load(sys.stdin).get('token',''))" 2>/dev/null \
    | tr -d '\r')"
  if [ -z "$token" ]; then
    echo "FAILED - no token from $base/api/v1/auth/token" >&2
    return 1
  fi

  codes="$(for _ in $(seq 1 "$probes"); do
    curl -s -m 5 -o /dev/null -w '%{http_code}\n' \
      -H "Authorization: Bearer $token" "$base/api/v1/trains/search?$params"
    sleep 0.3
  done | tr -d '\r')"   # mingw curl writes -w's \n as CRLF

  bad="$(printf '%s\n' "$codes" | grep -vc '^200$')"
  if [ "$bad" -gt 0 ]; then
    echo "FAILED" >&2
    echo "   $bad of $probes searches through $base did not return 200:" >&2
    printf '%s\n' "$codes" | sort | uniq -c | sed 's/^/     /' >&2
    echo "   404 = routed to a container without booking routes (stale upstream?)" >&2
    echo "   502 = a replica is down;  429 = another client is using this user" >&2
    return 1
  fi
  return 0
}
