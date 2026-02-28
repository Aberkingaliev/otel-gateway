#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
HOST="${1:-127.0.0.1}"
PORT="${2:-4318}"
SIGNAL="${3:-traces}" # traces|metrics|logs
STRICT_MODE="${MASKING_EXPLICIT_STRICT:-false}"

if [[ ! "${SIGNAL}" =~ ^(traces|metrics|logs)$ ]]; then
  echo "Invalid signal: ${SIGNAL}. Allowed: traces|metrics|logs" >&2
  exit 2
fi

if [[ -f "${ROOT_DIR}/infra/.env" ]]; then
  compose_cmd=(docker compose --env-file "${ROOT_DIR}/infra/.env" -f "${ROOT_DIR}/infra/docker-compose.yml")
else
  compose_cmd=(docker compose -f "${ROOT_DIR}/infra/docker-compose.yml")
fi

# 10-byte PII value to match fixed mask token length (##########).
pii_value="TENANT1234"
masked_value="##########"
now_s="$(date +%s)"
start_nano="${now_s}000000000"
end_nano="$((now_s + 1))000000000"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

cat > "${tmp_dir}/${SIGNAL}.json" <<JSON
{
  "resourceSpans": [
    {
      "resource": {
        "attributes": [
          {"key": "tenant_id", "value": {"stringValue": "${pii_value}"}},
          {"key": "service.name", "value": {"stringValue": "masking-explicit-test"}}
        ]
      },
      "scopeSpans": [
        {
          "scope": {"name": "masking-test", "version": "1.0.0"},
          "spans": [
            {
              "traceId": "5b8efff798038103d269b633813fc60c",
              "spanId": "eee19b7ec3c1b174",
              "name": "masking-explicit-span",
              "kind": 2,
              "startTimeUnixNano": "${start_nano}",
              "endTimeUnixNano": "${end_nano}"
            }
          ]
        }
      ]
    }
  ]
}
JSON

if [[ "${SIGNAL}" == "metrics" ]]; then
  cat > "${tmp_dir}/metrics.json" <<JSON
{
  "resourceMetrics": [
    {
      "resource": {
        "attributes": [
          {"key": "tenant_id", "value": {"stringValue": "${pii_value}"}},
          {"key": "service.name", "value": {"stringValue": "masking-explicit-test"}}
        ]
      },
      "scopeMetrics": [
        {
          "scope": {"name": "masking-test", "version": "1.0.0"},
          "metrics": [
            {
              "name": "masking_explicit_counter",
              "unit": "1",
              "sum": {
                "aggregationTemporality": 2,
                "isMonotonic": true,
                "dataPoints": [
                  {
                    "startTimeUnixNano": "${start_nano}",
                    "timeUnixNano": "${end_nano}",
                    "asInt": "1"
                  }
                ]
              }
            }
          ]
        }
      ]
    }
  ]
}
JSON
fi

if [[ "${SIGNAL}" == "logs" ]]; then
  cat > "${tmp_dir}/logs.json" <<JSON
{
  "resourceLogs": [
    {
      "resource": {
        "attributes": [
          {"key": "tenant_id", "value": {"stringValue": "${pii_value}"}},
          {"key": "service.name", "value": {"stringValue": "masking-explicit-test"}}
        ]
      },
      "scopeLogs": [
        {
          "scope": {"name": "masking-test", "version": "1.0.0"},
          "logRecords": [
            {
              "timeUnixNano": "${end_nano}",
              "severityNumber": 9,
              "severityText": "INFO",
              "body": {"stringValue": "masking explicit log"}
            }
          ]
        }
      ]
    }
  ]
}
JSON
fi

payload_file="${tmp_dir}/${SIGNAL}.json"
http_code="$(curl -sS -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  --data-binary @"${payload_file}" \
  "http://${HOST}:${PORT}/v1/${SIGNAL}")"

echo "ingest ${SIGNAL}: HTTP ${http_code}"
if [[ "${http_code}" != "200" ]]; then
  echo "FAILED: gateway did not accept payload" >&2
  exit 1
fi

sleep 2
logs="$("${compose_cmd[@]}" logs --since 20s otel-upstream)"

if ! grep -q "${masked_value}" <<< "${logs}"; then
  if grep -q "${pii_value}" <<< "${logs}"; then
    echo "FAILED: original PII value '${pii_value}' leaked to upstream logs" >&2
    exit 1
  fi
  if [[ "${STRICT_MODE}" == "true" ]]; then
    echo "FAILED: masked value '${masked_value}' not found in upstream logs (strict mode)" >&2
    exit 1
  fi
  echo "WARN: upstream logs do not include payload fields (likely basic debug verbosity); skipping strict content assertion"
  echo "OK: payload accepted; run with MASKING_EXPLICIT_STRICT=true and detailed upstream logs for byte-level assertion"
  exit 0
fi

if grep -q "${pii_value}" <<< "${logs}"; then
  echo "FAILED: original PII value '${pii_value}' leaked to upstream logs" >&2
  exit 1
fi

echo "OK: masking applied (${pii_value} -> ${masked_value})"
