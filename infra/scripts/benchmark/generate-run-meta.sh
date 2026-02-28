#!/usr/bin/env bash
set -euo pipefail

# Generates run-meta.json with full environment metadata for benchmark runs.

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --run-id <id>             Benchmark run ID (required)
  --track <c7i|c7g>         Hardware track (required)
  --scenario <name>         Scenario name (required)
  --artifact-dir <path>     Artifact output directory (required)
  --tf-dir <path>           Terraform directory (optional, for instance metadata)
  --duration <dur>          Workload duration (default: 60m)
  --workers <n>             Telemetrygen workers (default: 16)
  --rate <rps>              Telemetrygen rate per signal (default: 15000)
  --gateway-image <image>   Gateway image used
  --masking-rules-file <f>  Masking rules file path
  -h, --help                Show this help
USAGE
}

RUN_ID=""
TRACK=""
SCENARIO=""
ARTIFACT_DIR=""
TF_DIR=""
DURATION="60m"
WORKERS="16"
RATE="15000"
GATEWAY_IMAGE_ARG=""
MASKING_RULES_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-id)         RUN_ID="$2";          shift 2 ;;
    --track)          TRACK="$2";           shift 2 ;;
    --scenario)       SCENARIO="$2";        shift 2 ;;
    --artifact-dir)   ARTIFACT_DIR="$2";    shift 2 ;;
    --tf-dir)         TF_DIR="$2";          shift 2 ;;
    --duration)       DURATION="$2";        shift 2 ;;
    --workers)        WORKERS="$2";         shift 2 ;;
    --rate)           RATE="$2";            shift 2 ;;
    --gateway-image)  GATEWAY_IMAGE_ARG="$2"; shift 2 ;;
    --masking-rules-file) MASKING_RULES_FILE="$2"; shift 2 ;;
    -h|--help)        usage; exit 0 ;;
    *)                echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "${RUN_ID}" || -z "${TRACK}" || -z "${SCENARIO}" || -z "${ARTIFACT_DIR}" ]]; then
  echo "ERROR: --run-id, --track, --scenario, and --artifact-dir are required" >&2
  usage
  exit 1
fi

mkdir -p "${ARTIFACT_DIR}"

# Collect git info
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
GIT_SHA="$(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || echo "unknown")"

# Collect terraform metadata if available
INSTANCE_TYPE_GATEWAY=""
INSTANCE_TYPE_UPSTREAM=""
INSTANCE_TYPE_TELEMETRYGEN=""
AMI_ID=""
if [[ -n "${TF_DIR}" && -d "${TF_DIR}" ]]; then
  TF_JSON="$(cd "${TF_DIR}" && terraform output -json 2>/dev/null || echo "{}")"
  INSTANCE_TYPE_GATEWAY="$(echo "${TF_JSON}" | jq -r '.instance_types.value.gateway // empty' 2>/dev/null || true)"
  INSTANCE_TYPE_UPSTREAM="$(echo "${TF_JSON}" | jq -r '.instance_types.value.upstream // empty' 2>/dev/null || true)"
  INSTANCE_TYPE_TELEMETRYGEN="$(echo "${TF_JSON}" | jq -r '.instance_types.value.telemetrygen // empty' 2>/dev/null || true)"
  AMI_ID="$(echo "${TF_JSON}" | jq -r '.ami_id.value // empty' 2>/dev/null || true)"
fi

# Detect kernel
KERNEL_VERSION="$(uname -r 2>/dev/null || echo "unknown")"

# Detect vCPU count
VCPU="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo "0")"

# Gateway image digest
GATEWAY_IMAGE_DIGEST=""
if [[ -n "${GATEWAY_IMAGE_ARG}" ]]; then
  GATEWAY_IMAGE_DIGEST="$(docker inspect --format='{{index .RepoDigests 0}}' "${GATEWAY_IMAGE_ARG}" 2>/dev/null || echo "")"
fi

# Count masking rules
MASKING_RULES_COUNT=0
if [[ -n "${MASKING_RULES_FILE}" && -f "${MASKING_RULES_FILE}" ]]; then
  MASKING_RULES_COUNT="$(grep -c -v '^$' "${MASKING_RULES_FILE}" 2>/dev/null || echo "0")"
fi

# JVM options
JVM_OPTIONS="${JAVA_TOOL_OPTIONS:--XX:MaxRAMPercentage=75}"

# SIMD mode
SIMD_MODE="${GATEWAY_MASKING_SIMD:-on}"

# Build JSON
TIMESTAMP_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -cn \
  --arg run_id "${RUN_ID}" \
  --arg timestamp_utc "${TIMESTAMP_UTC}" \
  --arg track "${TRACK}" \
  --arg scenario "${SCENARIO}" \
  --arg git_sha "${GIT_SHA}" \
  --arg image_digest "${GATEWAY_IMAGE_DIGEST}" \
  --arg jvm_options "${JVM_OPTIONS}" \
  --arg gw_instance_type "${INSTANCE_TYPE_GATEWAY}" \
  --argjson vcpu "${VCPU}" \
  --arg up_instance_type "${INSTANCE_TYPE_UPSTREAM}" \
  --arg tg_instance_type "${INSTANCE_TYPE_TELEMETRYGEN}" \
  --arg ami_id "${AMI_ID}" \
  --arg kernel_version "${KERNEL_VERSION}" \
  --arg duration "${DURATION}" \
  --argjson workers "${WORKERS}" \
  --argjson rate "${RATE}" \
  --argjson masking_rules_count "${MASKING_RULES_COUNT}" \
  --arg masking_simd_mode "${SIMD_MODE}" \
  --arg max_inflight "${GATEWAY_MAX_INFLIGHT:-8192}" \
  --arg exporter_pool_size "${GATEWAY_EXPORTER_POOL_SIZE:-64}" \
  --arg exporter_io_threads "${GATEWAY_EXPORTER_IO_THREADS:-0}" \
  '{
    run_id: $run_id,
    timestamp_utc: $timestamp_utc,
    track: $track,
    scenario: $scenario,
    gateway: {
      git_sha: $git_sha,
      image_digest: $image_digest,
      jvm_options: $jvm_options,
      instance_type: $gw_instance_type,
      vcpu: $vcpu
    },
    upstream: {
      instance_type: $up_instance_type
    },
    telemetrygen: {
      instance_type: $tg_instance_type
    },
    ami_id: $ami_id,
    kernel_version: $kernel_version,
    env_vars: {
      GATEWAY_MAX_INFLIGHT: $max_inflight,
      GATEWAY_EXPORTER_POOL_SIZE: $exporter_pool_size,
      GATEWAY_EXPORTER_IO_THREADS: $exporter_io_threads
    },
    workload: {
      duration: $duration,
      workers: $workers,
      rate: $rate
    },
    masking_rules_count: $masking_rules_count,
    masking_simd_mode: $masking_simd_mode
  }' >"${ARTIFACT_DIR}/run-meta.json"

echo "Generated: ${ARTIFACT_DIR}/run-meta.json"
