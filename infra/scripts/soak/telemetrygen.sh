#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

OTLP_ENDPOINT="${OTLP_ENDPOINT:-host.docker.internal:4318}"
METRICS_URL="${METRICS_URL:-http://127.0.0.1:9464/metrics}"
IMAGE="${TELEMETRYGEN_IMAGE:-ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:latest}"
CAPTURE_GATEWAY_LOGS="${CAPTURE_GATEWAY_LOGS:-true}"
CAPTURE_UPSTREAM_LOGS="${CAPTURE_UPSTREAM_LOGS:-false}"
CAPTURE_METRICS="${CAPTURE_METRICS:-true}"
WAIT_FOR_METRICS="${WAIT_FOR_METRICS:-true}"
WAIT_TIMEOUT_SEC="${WAIT_TIMEOUT_SEC:-60}"

DURATION="${1:-60m}"
WORKERS="${2:-16}"
RATE="${3:-15000}"
METRICS_INTERVAL_SEC="${4:-10}"
OUT_DIR="${5:-${ROOT_DIR}/infra/artifacts/soak-$(date +%Y%m%d_%H%M%S)}"

TENANT_ID="${SOAK_TENANT_ID:-black_list}"

mkdir -p "${OUT_DIR}"

if [[ -f "${ROOT_DIR}/infra/.env" ]]; then
  compose_cmd=(docker compose --env-file "${ROOT_DIR}/infra/.env" -f "${ROOT_DIR}/infra/docker-compose.yml")
else
  compose_cmd=(docker compose -f "${ROOT_DIR}/infra/docker-compose.yml")
fi

echo "Soak config:"
echo "  duration=${DURATION} workers=${WORKERS} rate=${RATE} metrics_interval_sec=${METRICS_INTERVAL_SEC}"
echo "  otlp_endpoint=${OTLP_ENDPOINT}"
echo "  metrics_url=${METRICS_URL}"
echo "  tenant_id=${TENANT_ID}"
echo "  out_dir=${OUT_DIR}"
echo "  capture_gateway_logs=${CAPTURE_GATEWAY_LOGS} capture_upstream_logs=${CAPTURE_UPSTREAM_LOGS} capture_metrics=${CAPTURE_METRICS}"

bg_pids=()

cleanup() {
  for pid in "${bg_pids[@]:-}"; do
    kill "${pid}" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT INT TERM

start_log_capture() {
  local service="$1"
  local file="$2"
  "${compose_cmd[@]}" logs -f --no-color --tail=0 "${service}" >"${file}" 2>&1 &
  bg_pids+=("$!")
}

start_metrics_capture() {
  local file="$1"
  (
    while true; do
      printf "# ts=%s\n" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      curl -fsS "${METRICS_URL}" || true
      printf "\n"
      sleep "${METRICS_INTERVAL_SEC}"
    done
  ) >"${file}" 2>&1 &
  bg_pids+=("$!")
}

if [[ "${CAPTURE_GATEWAY_LOGS}" == "true" ]]; then
  start_log_capture gateway "${OUT_DIR}/gateway.log"
fi
if [[ "${CAPTURE_UPSTREAM_LOGS}" == "true" ]]; then
  start_log_capture otel-upstream "${OUT_DIR}/upstream.log"
fi
if [[ "${CAPTURE_METRICS}" == "true" ]]; then
  start_metrics_capture "${OUT_DIR}/metrics.prom"
fi

echo "Started background capture in ${OUT_DIR}"

run_telemetrygen() {
  local signal="$1"
  local path="/v1/${signal}"
  docker run --rm "${IMAGE}" "${signal}" \
    --otlp-http \
    --otlp-insecure \
    --otlp-endpoint "${OTLP_ENDPOINT}" \
    --otlp-http-url-path "${path}" \
    --duration "${DURATION}" \
    --workers "${WORKERS}" \
    --rate "${RATE}" \
    --service "soak-${signal}" \
    --otlp-attributes "tenant_id=\"${TENANT_ID}\"" \
    --telemetry-attributes "tenant_id=\"${TENANT_ID}\"" \
    --telemetry-attributes "env=\"soak\""
}

wait_for_gateway_metrics() {
  local deadline=$((SECONDS + WAIT_TIMEOUT_SEC))
  while (( SECONDS < deadline )); do
    if curl -fsS "${METRICS_URL}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

if [[ "${WAIT_FOR_METRICS}" == "true" ]]; then
  echo "Waiting for gateway metrics endpoint..."
  if ! wait_for_gateway_metrics; then
    echo "FAILED: metrics endpoint not ready at ${METRICS_URL} within ${WAIT_TIMEOUT_SEC}s" >&2
    exit 1
  fi
fi

run_telemetrygen traces >"${OUT_DIR}/telemetrygen-traces.log" 2>&1 &
pid_traces=$!
run_telemetrygen metrics >"${OUT_DIR}/telemetrygen-metrics.log" 2>&1 &
pid_metrics=$!
run_telemetrygen logs >"${OUT_DIR}/telemetrygen-logs.log" 2>&1 &
pid_logs=$!

set +e
wait "${pid_traces}"; rc_traces=$?
wait "${pid_metrics}"; rc_metrics=$?
wait "${pid_logs}"; rc_logs=$?
set -e

sleep 2

overall_rc=0
if [[ "${rc_traces}" -ne 0 || "${rc_metrics}" -ne 0 || "${rc_logs}" -ne 0 ]]; then
  overall_rc=1
fi

summary_file="${OUT_DIR}/summary.txt"
diagnostics_file="${OUT_DIR}/diagnostics.txt"

{
  echo "# soak diagnostics"
  if [[ -f "${OUT_DIR}/gateway.log" ]]; then
    echo "## gateway log scan"
    rg -n -i "LEAK|refCnt|OutOfDirectMemoryError|Double release|Exception" "${OUT_DIR}/gateway.log" || true
  else
    echo "gateway.log not found"
  fi
  if [[ -f "${OUT_DIR}/metrics.prom" ]]; then
    echo "## metrics scan"
    rg -n "gateway_(packets_processed_total|queue_depth|dropped_total|audit.*|end_to_end_duration_nanos|mask_writer_active|masking_simd_)" "${OUT_DIR}/metrics.prom" | tail -n 200 || true
  else
    echo "metrics.prom not found"
  fi
} >"${diagnostics_file}"

{
  echo "telemetrygen_exit_codes traces=${rc_traces} metrics=${rc_metrics} logs=${rc_logs}"
  echo "artifact_dir=${OUT_DIR}"
  echo "diagnostics_file=${diagnostics_file}"
  echo "quick-grep:"
  if [[ "${CAPTURE_GATEWAY_LOGS}" == "true" ]]; then
    echo "  rg -n \"LEAK|refCnt|OutOfDirectMemoryError|Exception\" \"${OUT_DIR}/gateway.log\""
  fi
  if [[ "${CAPTURE_METRICS}" == "true" ]]; then
    echo "  rg -n \"gateway_(packets_processed_total|queue_depth|dropped_total|audit.*|end_to_end_duration_nanos)\" \"${OUT_DIR}/metrics.prom\" | tail -n 50"
  fi
  if [[ "${CAPTURE_UPSTREAM_LOGS}" == "true" ]]; then
    echo "  rg -n \"Traces|Metrics|Logs\" \"${OUT_DIR}/upstream.log\" | tail -n 50"
  fi
} >"${summary_file}"

cat "${summary_file}"
exit "${overall_rc}"
