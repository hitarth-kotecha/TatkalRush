#!/usr/bin/env bash
# Re-resolve the pinned image digests in compose.yaml and Dockerfile.
#
# Digests are pinned deliberately (SDD §8.4, DD-003) - preview classfiles refuse
# to load on a different JDK major, and a floating base image would swap the JVM
# under a committed benchmark. So this is the ONLY way they should ever change:
# run it, read the diff, and record why in docs/design-decisions.md.
#
# Never edit a digest by hand. A hand-typed digest that happens to be wrong fails
# at pull time with a message about the manifest, not about you.
#
# Usage:  ./ops/docker/refresh-digests.sh [--check]
#           (no args)  rewrite the digests in place
#           --check    report drift and exit non-zero; for CI
set -euo pipefail

cd "$(dirname "$0")/../.."

CHECK_ONLY=false
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=true

# Tag -> the file(s) whose digest lines should be rewritten.
declare -A IMAGES=(
  ["maven:3.9-eclipse-temurin-25"]="Dockerfile"
  ["eclipse-temurin:25-jre"]="Dockerfile"
  ["postgres:16-alpine"]="compose.yaml"
  ["redis:7-alpine"]="compose.yaml"
  ["apache/kafka:4.1.2"]="compose.yaml"
  ["nginx:1.27-alpine"]="compose.yaml"
  ["prom/prometheus:v3.1.0"]="compose.yaml"
  ["grafana/grafana:11.5.0"]="compose.yaml"
  ["ghcr.io/shopify/toxiproxy:2.11.0"]="compose.yaml"
)

drift=0

for tag in "${!IMAGES[@]}"; do
  file="${IMAGES[$tag]}"
  repo="${tag%%:*}"

  digest="$(docker manifest inspect -v "$tag" 2>/dev/null \
    | python -c "import sys,json;o=json.load(sys.stdin);print(o['Descriptor']['digest'] if isinstance(o,dict) else o[0]['Descriptor']['digest'])")"

  if [[ -z "$digest" ]]; then
    echo "FAILED to resolve $tag" >&2
    exit 1
  fi

  current="$(grep -oE "${repo}@sha256:[0-9a-f]{64}" "$file" | head -1 | sed 's/.*@//' || true)"

  if [[ "$current" == "$digest" ]]; then
    printf '  %-38s unchanged\n' "$tag"
    continue
  fi

  drift=1
  printf '  %-38s %s -> %s\n' "$tag" "${current:-none}" "$digest"

  if [[ "$CHECK_ONLY" == false ]]; then
    python - "$file" "$repo" "$digest" <<'PY'
import io, re, sys
path, repo, digest = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(path, encoding="utf-8").read()
s = re.sub(re.escape(repo) + r"@sha256:[0-9a-f]{64}", repo + "@" + digest, s)
io.open(path, "w", encoding="utf-8", newline="\n").write(s)
PY
  fi
done

if [[ "$CHECK_ONLY" == true && $drift -ne 0 ]]; then
  echo "Pinned digests are stale. Run without --check, review the diff, and log the reason." >&2
  exit 1
fi

echo "done."
