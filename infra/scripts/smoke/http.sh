#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
PORT="${2:-4318}"

TMP_PAYLOAD="$(mktemp)"
trap 'rm -f "${TMP_PAYLOAD}"' EXIT

# Minimal protobuf payload (not semantic OTLP-valid, but enough for transport/path smoke).
printf '\x0a\x00' > "${TMP_PAYLOAD}"

for path in traces metrics logs; do
  code=$(curl -sS -o /dev/null -w "%{http_code}" \
    -H "Content-Type: application/x-protobuf" \
    --data-binary @"${TMP_PAYLOAD}" \
    "http://${HOST}:${PORT}/v1/${path}")
  echo "${path}: HTTP ${code}"
done
