#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
PORT="${2:-4318}"

# Build deterministic but valid OTLP timestamps (nanoseconds).
now_s=$(date +%s)
start_nano="${now_s}000000000"
end_nano="$((now_s + 1))000000000"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

cat > "${tmp_dir}/traces.json" <<JSON
{
  "resourceSpans": [
    {
      "resource": {
        "attributes": [
          {"key": "service.name", "value": {"stringValue": "infra-smoke-gateway"}}
        ]
      },
      "scopeSpans": [
        {
          "scope": {"name": "infra-smoke", "version": "1.0.0"},
          "spans": [
            {
              "traceId": "5b8efff798038103d269b633813fc60c",
              "spanId": "eee19b7ec3c1b174",
              "name": "infra-smoke-span",
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

cat > "${tmp_dir}/metrics.json" <<JSON
{
  "resourceMetrics": [
    {
      "resource": {
        "attributes": [
          {"key": "service.name", "value": {"stringValue": "infra-smoke-gateway"}}
        ]
      },
      "scopeMetrics": [
        {
          "scope": {"name": "infra-smoke", "version": "1.0.0"},
          "metrics": [
            {
              "name": "infra_smoke_counter",
              "unit": "1",
              "sum": {
                "aggregationTemporality": 2,
                "isMonotonic": true,
                "dataPoints": [
                  {
                    "attributes": [
                      {"key": "env", "value": {"stringValue": "smoke"}}
                    ],
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

cat > "${tmp_dir}/logs.json" <<JSON
{
  "resourceLogs": [
    {
      "resource": {
        "attributes": [
          {"key": "service.name", "value": {"stringValue": "infra-smoke-gateway"}}
        ]
      },
      "scopeLogs": [
        {
          "scope": {"name": "infra-smoke", "version": "1.0.0"},
          "logRecords": [
            {
              "timeUnixNano": "${end_nano}",
              "severityNumber": 9,
              "severityText": "INFO",
              "body": {"stringValue": "infra smoke log record"},
              "attributes": [
                {"key": "env", "value": {"stringValue": "smoke"}}
              ]
            }
          ]
        }
      ]
    }
  ]
}
JSON

send_one() {
  local signal="$1"
  local payload="$2"

  local body_file="${tmp_dir}/${signal}.response"
  local code
  code=$(curl -sS -o "${body_file}" -w "%{http_code}" \
    -H "Content-Type: application/json" \
    --data-binary @"${payload}" \
    "http://${HOST}:${PORT}/v1/${signal}")

  echo "${signal}: HTTP ${code}"
  if [[ -s "${body_file}" ]]; then
    echo "${signal} response body:"
    sed -n '1,40p' "${body_file}"
  fi
}

send_one traces "${tmp_dir}/traces.json"
send_one metrics "${tmp_dir}/metrics.json"
send_one logs "${tmp_dir}/logs.json"
