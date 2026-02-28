#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MEASURE_DIR="${1:-}"
OUT_FILE="${2:-}"

if [[ -z "${MEASURE_DIR}" ]]; then
  echo "Usage: $0 <measure-artifact-dir> [out-file]" >&2
  exit 1
fi

SUMMARY="${MEASURE_DIR}/finops-summary.txt"
METRICS_TS="${MEASURE_DIR}/metrics.prom"
if [[ ! -f "${SUMMARY}" ]]; then
  echo "FAILED: summary not found: ${SUMMARY}" >&2
  exit 1
fi

if [[ -z "${OUT_FILE}" ]]; then
  OUT_FILE="${MEASURE_DIR}/bottleneck-topn.txt"
fi

read_key() {
  local key="$1"
  awk -F '=' -v k="$key" '$1 == k { print $2; found=1; exit } END { if (!found) print "" }' "${SUMMARY}"
}

max_queue_depth=0
if [[ -f "${METRICS_TS}" ]]; then
  max_queue_depth="$(awk '
    BEGIN { max = 0; found = 0; }
    /^gateway_queue_depth / {
      if ($2 + 0 > max) max = $2 + 0;
      found=1;
    }
    END { if (found) print max; else print 0 }
  ' "${METRICS_TS}")"
fi

delta_received="$(read_key delta_received)"
delta_accepted="$(read_key delta_accepted)"
delta_dropped="$(read_key delta_dropped)"
avg_e2e_ms="$(read_key avg_e2e_ms)"
p99_e2e_ms="$(read_key p99_e2e_ms)"
leak_markers="$(read_key log_leak_markers)"
refcnt_markers="$(read_key log_refcnt_markers)"
double_release_markers="$(read_key log_double_release_markers)"
oom_markers="$(read_key log_out_of_direct_memory_markers)"
simd_requested_mode="$(read_key simd_requested_mode)"
simd_active_metric="$(read_key simd_active_metric)"
simd_strict_metric="$(read_key simd_strict_metric)"

tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT

add_issue() {
  local severity="$1"
  local title="$2"
  local detail="$3"
  printf "%s|%s|%s\n" "${severity}" "${title}" "${detail}" >> "${tmp}"
}

if [[ "${simd_requested_mode}" == "on" && "${simd_active_metric}" != "1" ]]; then
  add_issue "P1" "SIMD not active in strict mode" "requested_mode=on but simd_active_metric=${simd_active_metric}"
fi
if [[ "${simd_requested_mode}" == "on" && "${simd_strict_metric}" != "1" ]]; then
  add_issue "P1" "SIMD strict metric mismatch" "simd_strict_metric=${simd_strict_metric}"
fi

if [[ "${oom_markers:-0}" != "0" ]]; then
  add_issue "P1" "Direct memory pressure" "OutOfDirectMemoryError markers=${oom_markers}"
fi
if [[ "${double_release_markers:-0}" != "0" ]]; then
  add_issue "P1" "Refcount corruption signal" "Double release markers=${double_release_markers}"
fi
if [[ "${leak_markers:-0}" != "0" ]]; then
  add_issue "P2" "Potential ByteBuf leaks" "LEAK markers=${leak_markers}"
fi
if [[ "${refcnt_markers:-0}" != "0" ]]; then
  add_issue "P2" "Netty refCnt warnings" "refCnt markers=${refcnt_markers}"
fi

if [[ -n "${avg_e2e_ms}" && "${avg_e2e_ms}" != "n/a" ]]; then
  if awk -v v="${avg_e2e_ms}" 'BEGIN { exit !(v + 0 >= 2.0) }'; then
    add_issue "P2" "Latency budget risk" "avg_e2e_ms=${avg_e2e_ms} (target proxy <2ms)"
  elif awk -v v="${avg_e2e_ms}" 'BEGIN { exit !(v + 0 >= 1.0) }'; then
    add_issue "P3" "Latency watchlist" "avg_e2e_ms=${avg_e2e_ms}"
  fi
fi

if [[ -n "${p99_e2e_ms}" && "${p99_e2e_ms}" != "n/a" ]]; then
  if awk -v v="${p99_e2e_ms}" 'BEGIN { exit !(v + 0 >= 1.0) }'; then
    add_issue "P2" "P99 latency budget risk" "p99_e2e_ms=${p99_e2e_ms} (target <1.0ms)"
  elif awk -v v="${p99_e2e_ms}" 'BEGIN { exit !(v + 0 >= 0.5) }'; then
    add_issue "P3" "P99 latency watchlist" "p99_e2e_ms=${p99_e2e_ms}"
  fi
fi

if [[ -n "${max_queue_depth}" ]] && awk -v q="${max_queue_depth}" 'BEGIN { exit !(q >= 24576) }'; then
  add_issue "P2" "Queue saturation risk" "max_queue_depth=${max_queue_depth} (>= high watermark 24576)"
elif [[ -n "${max_queue_depth}" ]] && awk -v q="${max_queue_depth}" 'BEGIN { exit !(q >= 16384) }'; then
  add_issue "P3" "Queue pressure" "max_queue_depth=${max_queue_depth}"
fi

if [[ -n "${delta_received}" && -n "${delta_accepted}" && -n "${delta_dropped}" ]]; then
  if awk -v d="${delta_dropped}" -v r="${delta_received}" 'BEGIN { exit !(r > 0 && d / r > 0.60) }'; then
    add_issue "P2" "High drop ratio" "delta_dropped=${delta_dropped}, delta_received=${delta_received}"
  fi
fi

{
  echo "# Pre-prod Bottleneck Triage"
  echo "summary=${SUMMARY}"
  echo "metrics_timeseries=${METRICS_TS}"
  echo
  if [[ -s "${tmp}" ]]; then
    echo "## Top findings"
    i=0
    while IFS='|' read -r severity title detail; do
      i=$((i + 1))
      echo "${i}. [${severity}] ${title}: ${detail}"
    done < <(sort -t'|' -k1,1 "${tmp}")
  else
    echo "## Top findings"
    echo "1. [OK] No bottleneck heuristics triggered in current artifacts."
  fi
  echo
  echo "## Raw indicators"
  echo "- delta_received=${delta_received}"
  echo "- delta_accepted=${delta_accepted}"
  echo "- delta_dropped=${delta_dropped}"
  echo "- avg_e2e_ms=${avg_e2e_ms}"
  echo "- p99_e2e_ms=${p99_e2e_ms}"
  echo "- max_queue_depth=${max_queue_depth}"
  echo "- simd_requested_mode=${simd_requested_mode}"
  echo "- simd_active_metric=${simd_active_metric}"
  echo "- simd_strict_metric=${simd_strict_metric}"
  echo "- log_leak_markers=${leak_markers}"
  echo "- log_refcnt_markers=${refcnt_markers}"
  echo "- log_double_release_markers=${double_release_markers}"
  echo "- log_out_of_direct_memory_markers=${oom_markers}"
} > "${OUT_FILE}"

echo "Wrote triage report: ${OUT_FILE}"
