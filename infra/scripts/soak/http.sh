#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
PORT="${2:-4318}"
TOTAL="${3:-10000}"
CONCURRENCY="${4:-64}"

TMP_PAYLOAD="$(mktemp)"
TMP_RESULTS="$(mktemp)"
trap 'rm -f "${TMP_PAYLOAD}" "${TMP_RESULTS}"' EXIT

printf '\x0a\x00' > "${TMP_PAYLOAD}"

export HOST PORT TMP_PAYLOAD
seq "${TOTAL}" | xargs -P "${CONCURRENCY}" -I{} bash -c '
  code=$(curl -sS -o /dev/null -w "%{http_code}" \
    -H "Content-Type: application/x-protobuf" \
    --data-binary @"${TMP_PAYLOAD}" \
    "http://${HOST}:${PORT}/v1/traces" || echo "000")
  echo "$code"
' > "${TMP_RESULTS}"

ok=$(grep -c '^200$' "${TMP_RESULTS}" || true)
fail=$((TOTAL - ok))

echo "total=${TOTAL} concurrency=${CONCURRENCY} ok=${ok} fail=${fail}"
if [[ "${fail}" -gt 0 ]]; then
  echo "Top non-200 codes:"
  grep -v '^200$' "${TMP_RESULTS}" | sort | uniq -c | sort -nr | head -n 10
fi
