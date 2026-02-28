#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STACK_ENV_FILE="${STACK_ENV_FILE:-${ROOT_DIR}/infra/.env.preprod}"
if [[ ! -f "${STACK_ENV_FILE}" ]]; then
  STACK_ENV_FILE="${ROOT_DIR}/infra/.env.preprod.example"
fi

WARMUP_DURATION="${WARMUP_DURATION:-15m}"
MEASURE_DURATION="${MEASURE_DURATION:-60m}"
COOLDOWN_DURATION="${COOLDOWN_DURATION:-5m}"
WORKERS="${WORKERS:-16}"
RATE="${RATE:-15000}"
METRICS_INTERVAL_SEC="${METRICS_INTERVAL_SEC:-10}"
OUT_DIR="${1:-${ROOT_DIR}/infra/artifacts/preprod-hardening-$(date +%Y%m%d_%H%M%S)}"
mkdir -p "${OUT_DIR}"

compose_cmd=(docker compose --env-file "${STACK_ENV_FILE}" -f "${ROOT_DIR}/infra/docker-compose.yml")

read_key() {
  local file="$1"
  local key="$2"
  awk -F '=' -v k="$key" '$1 == k { print $2; found=1; exit } END { if (!found) print "" }' "${file}"
}

to_bytes() {
  local raw="$1"
  awk -v v="${raw}" '
    BEGIN {
      gsub(/[[:space:]]+/, "", v);
      if (v ~ /KiB$/) { sub(/KiB$/, "", v); printf "%.0f\n", v * 1024; exit; }
      if (v ~ /MiB$/) { sub(/MiB$/, "", v); printf "%.0f\n", v * 1024 * 1024; exit; }
      if (v ~ /GiB$/) { sub(/GiB$/, "", v); printf "%.0f\n", v * 1024 * 1024 * 1024; exit; }
      if (v ~ /B$/)   { sub(/B$/, "", v); printf "%.0f\n", v; exit; }
      print 0;
    }
  '
}

gateway_mem_used_bytes() {
  local gateway_id
  gateway_id="$("${compose_cmd[@]}" ps -q gateway | head -n 1)"
  if [[ -z "${gateway_id}" ]]; then
    echo "0"
    return
  fi
  local usage
  usage="$(docker stats --no-stream --format '{{.MemUsage}}' "${gateway_id}" | head -n 1)"
  if [[ -z "${usage}" ]]; then
    echo "0"
    return
  fi
  local used="${usage%%/*}"
  to_bytes "${used}"
}

run_stage() {
  local name="$1"
  local duration="$2"
  local stage_dir="${OUT_DIR}/${name}"
  mkdir -p "${stage_dir}"
  echo "[preprod] stage=${name} duration=${duration} out=${stage_dir}"
  STACK_ENV_FILE="${STACK_ENV_FILE}" \
  GATEWAY_MASKING_SIMD_OVERRIDE=on \
  LEAK_DETECTION_LEVEL=simple \
  "${ROOT_DIR}/infra/scripts/soak/finops-realistic.sh" \
    "${duration}" "${WORKERS}" "${RATE}" "${METRICS_INTERVAL_SEC}" "${stage_dir}"
}

echo "[preprod] using env file: ${STACK_ENV_FILE}"
echo "[preprod] warmup=${WARMUP_DURATION} measure=${MEASURE_DURATION} cooldown=${COOLDOWN_DURATION}"
echo "[preprod] workers=${WORKERS} rate=${RATE} metrics_interval_sec=${METRICS_INTERVAL_SEC}"
echo "[preprod] out_dir=${OUT_DIR}"

run_stage "warmup" "${WARMUP_DURATION}"
mem_before_bytes="$(gateway_mem_used_bytes)"
run_stage "measure" "${MEASURE_DURATION}"
mem_after_bytes="$(gateway_mem_used_bytes)"
run_stage "cooldown" "${COOLDOWN_DURATION}"

measure_summary="${OUT_DIR}/measure/finops-summary.txt"
triage_report="${OUT_DIR}/measure/bottleneck-topn.txt"
gate_report="${OUT_DIR}/go-no-go.txt"

"${ROOT_DIR}/infra/scripts/preprod/bottleneck-triage.sh" "${OUT_DIR}/measure" "${triage_report}"

growth_pct="n/a"
if [[ "${mem_before_bytes}" != "0" ]]; then
  growth_pct="$(awk -v before="${mem_before_bytes}" -v after="${mem_after_bytes}" '
    BEGIN {
      if (before <= 0) { print "n/a"; exit; }
      printf "%.4f", ((after - before) / before) * 100.0;
    }'
  )"
fi

simd_active="$(read_key "${measure_summary}" "simd_active_metric")"
simd_strict="$(read_key "${measure_summary}" "simd_strict_metric")"
leaks="$(read_key "${measure_summary}" "log_leak_markers")"
refcnt="$(read_key "${measure_summary}" "log_refcnt_markers")"
double_release="$(read_key "${measure_summary}" "log_double_release_markers")"
ooms="$(read_key "${measure_summary}" "log_out_of_direct_memory_markers")"
avg_e2e_ms="$(read_key "${measure_summary}" "avg_e2e_ms")"
p99_e2e_ms="$(read_key "${measure_summary}" "p99_e2e_ms")"

gate_status="PASS"
gate_reasons=()

if [[ "${simd_active}" != "1" || "${simd_strict}" != "1" ]]; then
  gate_status="FAIL"
  gate_reasons+=("SIMD strict gate failed (active=${simd_active}, strict=${simd_strict})")
fi
if [[ "${leaks:-0}" != "0" || "${refcnt:-0}" != "0" || "${double_release:-0}" != "0" || "${ooms:-0}" != "0" ]]; then
  gate_status="FAIL"
  gate_reasons+=("Leak/refcount/OOM diagnostics are non-zero")
fi
if [[ "${avg_e2e_ms}" != "n/a" ]] && awk -v v="${avg_e2e_ms}" 'BEGIN { exit !(v + 0 >= 2.0) }'; then
  gate_status="FAIL"
  gate_reasons+=("avg_e2e_ms gate failed (proxy threshold <2ms)")
fi
if [[ "${p99_e2e_ms}" != "n/a" && -n "${p99_e2e_ms}" ]] && awk -v v="${p99_e2e_ms}" 'BEGIN { exit !(v + 0 >= 1.0) }'; then
  gate_status="FAIL"
  gate_reasons+=("p99_e2e_ms gate failed (${p99_e2e_ms}ms >= 1.0ms)")
fi
if [[ "${growth_pct}" != "n/a" ]] && awk -v g="${growth_pct}" 'BEGIN { exit !(g > 5.0) }'; then
  gate_status="FAIL"
  gate_reasons+=("memory growth gate failed (${growth_pct}% > 5%)")
fi

{
  echo "# Pre-prod Go/No-Go"
  echo "status=${gate_status}"
  echo "measure_summary=${measure_summary}"
  echo "triage_report=${triage_report}"
  echo "memory_before_bytes=${mem_before_bytes}"
  echo "memory_after_bytes=${mem_after_bytes}"
  echo "memory_growth_pct=${growth_pct}"
  echo "simd_active_metric=${simd_active}"
  echo "simd_strict_metric=${simd_strict}"
  echo "avg_e2e_ms=${avg_e2e_ms}"
  echo "p99_e2e_ms=${p99_e2e_ms}"
  echo
  if [[ "${#gate_reasons[@]}" -eq 0 ]]; then
    echo "gate_reasons=none"
  else
    for reason in "${gate_reasons[@]}"; do
      echo "gate_reason=${reason}"
    done
  fi
  echo
  echo "notes:"
  echo "- p99_e2e_ms gate threshold: <1.0ms."
  echo "- avg_e2e_ms gate threshold: <2.0ms."
  echo "- Any P1/P2 finding from triage_report must block AWS testing."
} > "${gate_report}"

echo "Wrote gate report: ${gate_report}"
if [[ "${gate_status}" != "PASS" ]]; then
  exit 1
fi
