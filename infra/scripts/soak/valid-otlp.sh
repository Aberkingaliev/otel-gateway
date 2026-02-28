#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
PORT="${2:-4318}"
TOTAL="${3:-30000}"
CONCURRENCY="${4:-128}"
MODE="${5:-all}" # all|traces|metrics|logs

if [[ ! "${MODE}" =~ ^(all|traces|metrics|logs)$ ]]; then
  echo "Invalid mode: ${MODE}. Allowed: all|traces|metrics|logs" >&2
  exit 2
fi

now_s=$(date +%s)
start_nano="${now_s}000000000"
end_nano="$((now_s + 1))000000000"

TMP_DIR="$(mktemp -d)"
TMP_RESULTS="${TMP_DIR}/results.txt"
trap 'rm -rf "${TMP_DIR}"' EXIT

cat > "${TMP_DIR}/traces.json" <<JSON
{
  "resourceSpans": [
    {
      "resource": {
        "attributes": [
          {"key": "service.name", "value": {"stringValue": "infra-soak-gateway"}}
        ]
      },
      "scopeSpans": [
        {
          "scope": {"name": "infra-soak", "version": "1.0.0"},
          "spans": [
            {
              "traceId": "5b8efff798038103d269b633813fc60c",
              "spanId": "eee19b7ec3c1b174",
              "name": "infra-soak-span",
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

cat > "${TMP_DIR}/metrics.json" <<JSON
{
  "resourceMetrics": [
    {
      "resource": {
        "attributes": [
          {"key": "service.name", "value": {"stringValue": "infra-soak-gateway"}}
        ]
      },
      "scopeMetrics": [
        {
          "scope": {"name": "infra-soak", "version": "1.0.0"},
          "metrics": [
            {
              "name": "infra_soak_counter",
              "unit": "1",
              "sum": {
                "aggregationTemporality": 2,
                "isMonotonic": true,
                "dataPoints": [
                  {
                    "attributes": [
                      {"key": "env", "value": {"stringValue": "soak"}}
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

cat > "${TMP_DIR}/logs.json" <<JSON
{
  "resourceLogs": [
    {
      "resource": {
        "attributes": [
          {"key": "service.name", "value": {"stringValue": "infra-soak-gateway"}}
        ]
      },
      "scopeLogs": [
        {
          "scope": {"name": "infra-soak", "version": "1.0.0"},
          "logRecords": [
            {
              "timeUnixNano": "${end_nano}",
              "severityNumber": 9,
              "severityText": "INFO",
              "body": {"stringValue": "infra soak log record"},
              "attributes": [
                {"key": "env", "value": {"stringValue": "soak"}}
              ]
            }
          ]
        }
      ]
    }
  ]
}
JSON

export HOST PORT MODE TMP_DIR
seq "${TOTAL}" | xargs -P "${CONCURRENCY}" -I{} bash -c '
  i="$1"
  case "${MODE}" in
    traces|metrics|logs)
      signal="${MODE}"
      ;;
    all)
      case $((i % 3)) in
        0) signal="traces" ;;
        1) signal="metrics" ;;
        *) signal="logs" ;;
      esac
      ;;
  esac

  payload="${TMP_DIR}/${signal}.json"
  code=$(curl -sS -o /dev/null -w "%{http_code}" \
    -H "Content-Type: application/json" \
    --data-binary @"${payload}" \
    "http://${HOST}:${PORT}/v1/${signal}" || echo "000")
  echo "${signal}:${code}"
' _ {} > "${TMP_RESULTS}"

ok=$(grep -c ':200$' "${TMP_RESULTS}" || true)
fail=$((TOTAL - ok))

echo "total=${TOTAL} concurrency=${CONCURRENCY} mode=${MODE} ok=${ok} fail=${fail}"

for signal in traces metrics logs; do
  sent=$(grep -c "^${signal}:" "${TMP_RESULTS}" || true)
  if [[ "${sent}" -eq 0 ]]; then
    continue
  fi
  s_ok=$(grep -c "^${signal}:200$" "${TMP_RESULTS}" || true)
  s_fail=$((sent - s_ok))
  echo "${signal}: sent=${sent} ok=${s_ok} fail=${s_fail}"
  if [[ "${s_fail}" -gt 0 ]]; then
    echo "${signal} non-200 top:"
    grep "^${signal}:" "${TMP_RESULTS}" | sed 's/^.*://' | grep -v '^200$' | sort | uniq -c | sort -nr | head -n 10
  fi
  done

if [[ "${fail}" -gt 0 ]]; then
  echo "overall non-200 top:"
  sed 's/^.*://' "${TMP_RESULTS}" | grep -v '^200$' | sort | uniq -c | sort -nr | head -n 10
fi
