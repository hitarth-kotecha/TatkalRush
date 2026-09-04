#!/usr/bin/env bash
# AC-0.7 part 1: measure resident memory of the running stack against NFR-11.
#
# Samples `docker stats` over a window and reports per-container peak plus the
# stack total. A single instantaneous reading is not enough: the JVMs are still
# warming up for the first minute, and Kafka's page cache grows for longer than
# that, so an immediate sample understates the steady state.
#
# Usage:  ./ops/docker/measure-memory.sh [seconds] [interval]
set -euo pipefail

WINDOW="${1:-300}"
INTERVAL="${2:-30}"
BUDGET_MIB=4608          # NFR-11: 4.5 GB

cd "$(dirname "$0")/../.."

echo "Sampling for ${WINDOW}s every ${INTERVAL}s (NFR-11 budget ${BUDGET_MIB} MiB)"
echo

samples=$(( WINDOW / INTERVAL ))
raw="$(mktemp)"
trap 'rm -f "$raw"' EXIT

for i in $(seq 1 "$samples"); do
  docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}' >> "$raw"
  total=$(tail -n "$(docker ps -q | wc -l)" "$raw" \
    | awk -F'\t' '{split($2,a," / "); gsub(/MiB|GiB/,"",a[1]); if ($2 ~ /GiB \//) a[1]*=1024; s+=a[1]} END {printf "%.0f", s}')
  printf '  sample %2d/%d  total %s MiB\n' "$i" "$samples" "$total"
  [ "$i" -lt "$samples" ] && sleep "$INTERVAL"
done

to_mib() {
  # docker stats reports MiB or GiB depending on magnitude; normalise to MiB.
  awk -F'\t' '
    {
      split($2, a, " / ")
      v = a[1]
      if (v ~ /GiB/) { gsub(/GiB/, "", v); v = v * 1024 } else { gsub(/MiB/, "", v) }
      if (v + 0 > peak[$1]) peak[$1] = v + 0
    }
    END { for (n in peak) printf "%.1f\t%s\n", peak[n], n }
  ' "$1"
}

echo
echo "Per-container peak over the window:"
# Sorted here, on the per-container lines ONLY. Piping the whole awk block
# through sort put the RESULT line above the TOTAL it refers to.
to_mib "$raw" | sort -n | awk -F'\t' '{ printf "  %-28s %8.1f MiB\n", $2, $1 }'

total="$(to_mib "$raw" | awk -F'\t' '{s += $1} END {printf "%.1f", s}')"

echo
printf '  %-28s %8.1f MiB\n' "TOTAL (sum of peaks)" "$total"
printf '  %-28s %8d MiB\n' "NFR-11 budget" "$BUDGET_MIB"
echo

# Sum of per-container PEAKS, not the peak of the sum: peaks occur at different
# moments, so this over-counts slightly. That is the conservative direction, and
# NFR-11 is a ceiling rather than an estimate.
awk -v t="$total" -v b="$BUDGET_MIB" 'BEGIN {
  if (t > b) {
    printf "  RESULT: FAIL - over budget by %.1f MiB\n", t - b
    exit 1
  }
  printf "  RESULT: PASS - %.1f MiB headroom (%.0f%% of budget used)\n", b - t, 100 * t / b
}'
