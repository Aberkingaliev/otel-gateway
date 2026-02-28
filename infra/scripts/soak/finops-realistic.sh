#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

DURATION="${1:-10m}"
WORKERS="${2:-16}"
RATE="${3:-15000}"
METRICS_INTERVAL_SEC="${4:-10}"
OUT_DIR="${5:-${ROOT_DIR}/infra/artifacts/finops-soak-$(date +%Y%m%d_%H%M%S)}"

TENANT_ID="${SOAK_TENANT_ID:-black_list}"
METRICS_URL="${METRICS_URL:-http://127.0.0.1:9464/metrics}"
WAIT_TIMEOUT_SEC="${WAIT_TIMEOUT_SEC:-90}"
MASKING_SIMD_MODE="${GATEWAY_MASKING_SIMD_OVERRIDE:-on}"
LEAK_DETECTION_LEVEL="${LEAK_DETECTION_LEVEL:-simple}"
JAVA_TOOL_OPTIONS_VALUE="${JAVA_TOOL_OPTIONS_OVERRIDE:--XX:MaxRAMPercentage=75 -Dio.netty.leakDetection.level=${LEAK_DETECTION_LEVEL}}"

# Realistic policy profile for value demo:
# - redact tenant_id on 3 paths (trace resource, trace span, metrics resource)
# - drop logs carrying tenant_id (100% for workloads that always set tenant_id)
FINOPS_RULES="${GATEWAY_MASKING_RULES_OVERRIDE:-mask-tenant-trace-resource|TRACES|REDACT_MASK|resource.attributes.tenant_id|##########|10|skip|true;mask-tenant-trace-span|TRACES|REDACT_MASK|scopeSpans[*].spans[*].attributes.tenant_id|##########|11|skip|true;mask-tenant-metrics-resource|METRICS|REDACT_MASK|resourcemetrics.resource.attributes.tenant_id|##########|10|skip|true;drop-tenant-logs|LOGS|DROP|resourcelogs.resource.attributes.tenant_id||1|skip|true}"

fail_profile() {
  echo "FAILED: finops profile contract violation: $1" >&2
  exit 1
}

validate_finops_profile() {
  local rules="$1"
  local rules_lc
  local simd_mode_lc
  local mask_count

  simd_mode_lc="$(printf '%s' "${MASKING_SIMD_MODE}" | tr '[:upper:]' '[:lower:]')"
  if [[ "${simd_mode_lc}" != "on" ]]; then
    fail_profile "GATEWAY_MASKING_SIMD must be 'on' (current='${MASKING_SIMD_MODE}')"
  fi

  rules_lc="$(printf '%s' "${rules}" | tr '[:upper:]' '[:lower:]')"
  mask_count="$(printf '%s\n' "${rules}" | awk -F';' '
    {
      c=0;
      for (i=1; i<=NF; i++) {
        n=split($i, p, "\\|");
        if (n >= 3 && toupper(p[3]) == "REDACT_MASK") c++;
      }
      print c;
    }'
  )"
  if [[ "${mask_count}" -lt 3 ]]; then
    fail_profile "expected at least 3 REDACT_MASK rules, got ${mask_count}"
  fi

  if [[ "${rules_lc}" != *"|traces|redact_mask|resource.attributes.tenant_id|"* ]]; then
    fail_profile "missing required trace resource masking rule for resource.attributes.tenant_id"
  fi

  if [[ "${rules_lc}" != *"|traces|redact_mask|scopespans[*].spans[*].attributes.tenant_id|"* ]]; then
    fail_profile "missing required trace span masking rule for scopeSpans[*].spans[*].attributes.tenant_id"
  fi

  if [[ "${rules_lc}" != *"|metrics|redact_mask|resourcemetrics.resource.attributes.tenant_id|"* ]] \
    && [[ "${rules_lc}" != *"|metrics|redact_mask|resource.attributes.tenant_id|"* ]]; then
    fail_profile "missing required metrics masking rule for resource attributes tenant_id"
  fi

  if [[ "${rules_lc}" != *"|logs|drop|resourcelogs.resource.attributes.tenant_id|"* ]] \
    && [[ "${rules_lc}" != *"|logs|drop|resource.attributes.tenant_id|"* ]]; then
    fail_profile "missing required logs DROP rule for tenant_id attribute"
  fi
}

validate_finops_profile "${FINOPS_RULES}"

mkdir -p "${OUT_DIR}"
BEFORE="${OUT_DIR}/metrics-before.prom"
AFTER="${OUT_DIR}/metrics-after.prom"
SUMMARY="${OUT_DIR}/finops-summary.txt"

compose_env_file="${STACK_ENV_FILE:-${ROOT_DIR}/infra/.env}"
if [[ -f "${compose_env_file}" ]]; then
  compose_cmd=(docker compose --env-file "${compose_env_file}" -f "${ROOT_DIR}/infra/docker-compose.yml")
else
  compose_cmd=(docker compose -f "${ROOT_DIR}/infra/docker-compose.yml")
fi

echo "FinOps realistic soak config:"
echo "  duration=${DURATION} workers=${WORKERS} rate=${RATE} metrics_interval_sec=${METRICS_INTERVAL_SEC}"
echo "  tenant_id=${TENANT_ID}"
echo "  metrics_url=${METRICS_URL}"
echo "  out_dir=${OUT_DIR}"
echo "  masking_simd_mode=${MASKING_SIMD_MODE}"
echo "  java_tool_options=${JAVA_TOOL_OPTIONS_VALUE}"
echo "  rules=${FINOPS_RULES}"

echo "Restarting stack with FinOps policy profile..."
"${compose_cmd[@]}" down --remove-orphans >/dev/null 2>&1 || true
GATEWAY_MASKING_ENABLED=true \
GATEWAY_MASKING_SIMD="${MASKING_SIMD_MODE}" \
GATEWAY_MASKING_RULES="${FINOPS_RULES}" \
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS_VALUE}" \
"${compose_cmd[@]}" up -d --build

wait_for_gateway() {
  local deadline=$((SECONDS + WAIT_TIMEOUT_SEC))
  while (( SECONDS < deadline )); do
    if curl -fsS "${METRICS_URL}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

echo "Waiting for gateway readiness..."
if ! wait_for_gateway; then
  echo "FAILED: gateway metrics endpoint is not ready at ${METRICS_URL} within ${WAIT_TIMEOUT_SEC}s" >&2
  exit 1
fi

echo "Running smoke before soak..."
"${ROOT_DIR}/infra/scripts/smoke/valid-otlp.sh"
SOAK_TENANT_ID="${TENANT_ID}" "${ROOT_DIR}/infra/scripts/smoke/masking-explicit.sh" 127.0.0.1 4318 traces

curl -fsS "${METRICS_URL}" > "${BEFORE}"

echo "Running telemetrygen soak..."
SOAK_TENANT_ID="${TENANT_ID}" \
CAPTURE_UPSTREAM_LOGS="${CAPTURE_UPSTREAM_LOGS:-false}" \
"${ROOT_DIR}/infra/scripts/soak/telemetrygen.sh" "${DURATION}" "${WORKERS}" "${RATE}" "${METRICS_INTERVAL_SEC}" "${OUT_DIR}"

curl -fsS "${METRICS_URL}" > "${AFTER}"

metric_value() {
  local file="$1"
  local key="$2"
  awk -v k="$key" '$0 ~ "^"k" " {print $2; found=1; exit} END { if (!found) print 0 }' "$file"
}

metric_packets_status_all() {
  local file="$1"
  local status="$2"
  awk -v s="$status" '
    /^gateway_packets_processed_total\{/ {
      if ($0 ~ ("status=\"" s "\"") && $0 ~ /signal="ALL"/) {
        print $2;
        found=1;
        exit;
      }
    }
    END { if (!found) print 0 }
  ' "$file"
}

before_received="$(metric_packets_status_all "${BEFORE}" "received")"
after_received="$(metric_packets_status_all "${AFTER}" "received")"
before_accepted="$(metric_packets_status_all "${BEFORE}" "accepted")"
after_accepted="$(metric_packets_status_all "${AFTER}" "accepted")"
before_dropped="$(metric_packets_status_all "${BEFORE}" "dropped")"
after_dropped="$(metric_packets_status_all "${AFTER}" "dropped")"
before_e2e_count="$(metric_value "${BEFORE}" 'gateway_end_to_end_duration_nanos_count')"
after_e2e_count="$(metric_value "${AFTER}" 'gateway_end_to_end_duration_nanos_count')"
before_e2e_sum="$(metric_value "${BEFORE}" 'gateway_end_to_end_duration_nanos_sum')"
after_e2e_sum="$(metric_value "${AFTER}" 'gateway_end_to_end_duration_nanos_sum')"

delta_received=$((after_received - before_received))
delta_accepted=$((after_accepted - before_accepted))
delta_dropped=$((after_dropped - before_dropped))
delta_e2e_count=$((after_e2e_count - before_e2e_count))
delta_e2e_sum=$((after_e2e_sum - before_e2e_sum))

avg_e2e_ms="n/a"
if (( delta_e2e_count > 0 )); then
  avg_e2e_ms="$(awk -v s="$delta_e2e_sum" -v c="$delta_e2e_count" 'BEGIN { printf "%.6f", (s/c)/1000000.0 }')"
fi

p99_e2e_ns="$(awk '/^gateway_end_to_end_p99_nanos / {print $2}' "${AFTER}")"
p99_e2e_ms="$(awk -v v="${p99_e2e_ns:-0}" 'BEGIN { printf "%.6f", v/1000000.0 }')"

egress_savings_pct="n/a"
if (( delta_received > 0 )); then
  egress_savings_pct="$(awk -v d="${delta_dropped}" -v r="${delta_received}" 'BEGIN { printf "%.2f", (d/r)*100 }')"
fi

upstream_logs="${OUT_DIR}/upstream.log"
upstream_traces=0
upstream_metrics=0
upstream_logs_count=0
if [[ -f "${upstream_logs}" ]]; then
  upstream_traces="$(rg -c 'info[[:space:]]+Traces' "${upstream_logs}" || true)"
  upstream_metrics="$(rg -c 'info[[:space:]]+Metrics' "${upstream_logs}" || true)"
  upstream_logs_count="$(rg -c 'info[[:space:]]+Logs' "${upstream_logs}" || true)"
  upstream_traces="${upstream_traces:-0}"
  upstream_metrics="${upstream_metrics:-0}"
  upstream_logs_count="${upstream_logs_count:-0}"
fi

gateway_log="${OUT_DIR}/gateway.log"
diagnostics_report="${OUT_DIR}/diagnostics.txt"
leak_markers=0
refcnt_markers=0
double_release_markers=0
oom_markers=0
if [[ -f "${gateway_log}" ]]; then
  leak_markers="$(rg -i -c 'LEAK' "${gateway_log}" || true)"
  refcnt_markers="$(rg -i -c 'refCnt' "${gateway_log}" || true)"
  double_release_markers="$(rg -i -c 'Double release' "${gateway_log}" || true)"
  oom_markers="$(rg -i -c 'OutOfDirectMemoryError' "${gateway_log}" || true)"
  leak_markers="${leak_markers:-0}"
  refcnt_markers="${refcnt_markers:-0}"
  double_release_markers="${double_release_markers:-0}"
  oom_markers="${oom_markers:-0}"
fi

after_simd_active="$(awk '
  /^gateway_mask_writer_active\{/ {
    if ($0 ~ /active_writer="simd"/) {
      print $2;
      found=1;
      exit;
    }
  }
  END { if (!found) print 0 }
' "${AFTER}")"
after_simd_available="$(metric_value "${AFTER}" 'gateway_masking_simd_available')"
after_simd_strict="$(metric_value "${AFTER}" 'gateway_masking_simd_strict_mode')"

{
  echo "# gateway diagnostics"
  echo "pattern: LEAK|refCnt|OutOfDirectMemoryError|Double release"
  if [[ -f "${gateway_log}" ]]; then
    rg -n -i 'LEAK|refCnt|OutOfDirectMemoryError|Double release' "${gateway_log}" || true
  else
    echo "gateway.log not found"
  fi
} > "${diagnostics_report}"

{
  echo "delta_received=${delta_received}"
  echo "delta_accepted=${delta_accepted}"
  echo "delta_dropped=${delta_dropped}"
  echo "delta_e2e_count=${delta_e2e_count}"
  echo "avg_e2e_ms=${avg_e2e_ms}"
  echo "p99_e2e_ms=${p99_e2e_ms}"
  echo "egress_savings_pct=${egress_savings_pct}"
  echo "upstream_traces_events=${upstream_traces}"
  echo "upstream_metrics_events=${upstream_metrics}"
  echo "upstream_logs_events=${upstream_logs_count}"
  echo "simd_requested_mode=${MASKING_SIMD_MODE}"
  echo "simd_active_metric=${after_simd_active}"
  echo "simd_available_metric=${after_simd_available}"
  echo "simd_strict_metric=${after_simd_strict}"
  echo "log_leak_markers=${leak_markers}"
  echo "log_refcnt_markers=${refcnt_markers}"
  echo "log_double_release_markers=${double_release_markers}"
  echo "log_out_of_direct_memory_markers=${oom_markers}"
  echo "diagnostics_report=${diagnostics_report}"
  echo "artifact_dir=${OUT_DIR}"
} | tee "${SUMMARY}"

if (( delta_dropped <= 0 )); then
  echo "WARNING: expected dropped traffic but delta_dropped=${delta_dropped}" >&2
fi

if [[ "${MASKING_SIMD_MODE}" == "on" ]] && [[ "${after_simd_active}" != "1" ]]; then
  echo "FAILED: strict SIMD mode requested but simd active metric is ${after_simd_active}" >&2
  exit 1
fi
