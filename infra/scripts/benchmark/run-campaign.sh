#!/usr/bin/env bash
set -euo pipefail

# Campaign orchestrator for AWS benchmark runs.
# Coordinates multiple benchmark runs across scenarios and tracks.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --track <c7i|c7g>          Hardware track (required)
  --scenario <name>           Scenario: peak-rps|security-wall|finops-stress|otel-baseline|marathon-24h (required)
  --runs <n>                  Number of runs (default: 5; marathon/baseline default: 1)
  --duration <dur>            Soak duration (default: 60m; marathon: 24h)
  --workers <n>               Telemetrygen workers (default: 16)
  --rate <rps>                Telemetrygen rate per signal (default: 15000; marathon: 100000)
  --tf-dir <path>             Terraform directory (default: infra/terraform)
  --aws-profile <name>        AWS CLI profile
  --artifact-s3-uri <s3://..> S3 prefix for artifact upload
  --gateway-image <image>     Prebuilt gateway image
  --campaign-dir <path>       Campaign output directory (default: auto-generated)
  --metrics-interval-sec <n>  Metrics snapshot interval (default: 10; marathon: 60)
  --dry-run                   Print config without executing
  -h, --help                  Show this help

Scenarios:
  peak-rps        Standard 4 masking rules, peak throughput
  security-wall   12 wildcard-heavy masking rules (SIMD stress)
  finops-stress   4 rules with DROP LOGS (~33% egress savings)
  otel-baseline   Vanilla OTel Collector control group
  marathon-24h    24-hour endurance run
USAGE
}

TRACK=""
SCENARIO=""
RUNS=""
DURATION=""
WORKERS="16"
RATE=""
TF_DIR="${REPO_ROOT}/infra/terraform"
AWS_PROFILE=""
ARTIFACT_S3_URI=""
GATEWAY_IMAGE=""
CAMPAIGN_DIR=""
METRICS_INTERVAL_SEC=""
DRY_RUN="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --track)                TRACK="$2";              shift 2 ;;
    --scenario)             SCENARIO="$2";           shift 2 ;;
    --runs)                 RUNS="$2";               shift 2 ;;
    --duration)             DURATION="$2";           shift 2 ;;
    --workers)              WORKERS="$2";            shift 2 ;;
    --rate)                 RATE="$2";               shift 2 ;;
    --tf-dir)               TF_DIR="$2";             shift 2 ;;
    --aws-profile)          AWS_PROFILE="$2";        shift 2 ;;
    --artifact-s3-uri)      ARTIFACT_S3_URI="$2";    shift 2 ;;
    --gateway-image)        GATEWAY_IMAGE="$2";      shift 2 ;;
    --campaign-dir)         CAMPAIGN_DIR="$2";       shift 2 ;;
    --metrics-interval-sec) METRICS_INTERVAL_SEC="$2"; shift 2 ;;
    --dry-run)              DRY_RUN="true";          shift ;;
    -h|--help)              usage; exit 0 ;;
    *)                      echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "${TRACK}" || -z "${SCENARIO}" ]]; then
  echo "ERROR: --track and --scenario are required" >&2
  usage
  exit 1
fi

log() { printf '[campaign] %s\n' "$*"; }
fail() { printf '[campaign] ERROR: %s\n' "$*" >&2; exit 1; }

# Apply scenario defaults
case "${SCENARIO}" in
  peak-rps)
    RUNS="${RUNS:-5}"
    DURATION="${DURATION:-60m}"
    RATE="${RATE:-15000}"
    METRICS_INTERVAL_SEC="${METRICS_INTERVAL_SEC:-10}"
    SSM_TIMEOUT="7200"
    ;;
  security-wall)
    RUNS="${RUNS:-5}"
    DURATION="${DURATION:-60m}"
    RATE="${RATE:-15000}"
    METRICS_INTERVAL_SEC="${METRICS_INTERVAL_SEC:-10}"
    SSM_TIMEOUT="7200"
    ;;
  finops-stress)
    RUNS="${RUNS:-5}"
    DURATION="${DURATION:-60m}"
    RATE="${RATE:-15000}"
    METRICS_INTERVAL_SEC="${METRICS_INTERVAL_SEC:-10}"
    SSM_TIMEOUT="7200"
    ;;
  otel-baseline)
    RUNS="${RUNS:-1}"
    DURATION="${DURATION:-60m}"
    RATE="${RATE:-15000}"
    METRICS_INTERVAL_SEC="${METRICS_INTERVAL_SEC:-10}"
    SSM_TIMEOUT="7200"
    ;;
  marathon-24h)
    RUNS="${RUNS:-1}"
    DURATION="${DURATION:-24h}"
    RATE="${RATE:-100000}"
    METRICS_INTERVAL_SEC="${METRICS_INTERVAL_SEC:-60}"
    SSM_TIMEOUT="90000"
    ;;
  *)
    fail "Unknown scenario: ${SCENARIO}"
    ;;
esac

# Campaign directory
DATE_TAG="$(date +%Y-%m-%d)"
CAMPAIGN_DIR="${CAMPAIGN_DIR:-${REPO_ROOT}/infra/artifacts/benchmark-v2/${DATE_TAG}}"
SCENARIO_DIR="${CAMPAIGN_DIR}/${TRACK}/${SCENARIO}"

# Masking rules selection
MASKING_RULES=""
MASKING_RULES_FILE=""
case "${SCENARIO}" in
  peak-rps)
    MASKING_RULES="mask-tenant-trace-resource|TRACES|REDACT_MASK|resource.attributes.tenant_id|##########|10|skip|true;mask-tenant-trace-span|TRACES|REDACT_MASK|scopeSpans[*].spans[*].attributes.tenant_id|##########|11|skip|true;mask-tenant-metrics-resource|METRICS|REDACT_MASK|resourcemetrics.resource.attributes.tenant_id|##########|10|skip|true;drop-tenant-logs|LOGS|DROP|resourcelogs.resource.attributes.tenant_id||1|skip|true"
    ;;
  security-wall)
    MASKING_RULES_FILE="${REPO_ROOT}/infra/env/masking-security-wall.rules"
    if [[ ! -f "${MASKING_RULES_FILE}" ]]; then
      fail "Missing masking rules file: ${MASKING_RULES_FILE}"
    fi
    # Convert newline-separated rules file to semicolon-separated string
    MASKING_RULES="$(tr '\n' ';' < "${MASKING_RULES_FILE}" | sed 's/;$//')"
    ;;
  finops-stress)
    MASKING_RULES_FILE="${REPO_ROOT}/infra/env/masking-finops-stress.rules"
    if [[ ! -f "${MASKING_RULES_FILE}" ]]; then
      fail "Missing masking rules file: ${MASKING_RULES_FILE}"
    fi
    MASKING_RULES="$(tr '\n' ';' < "${MASKING_RULES_FILE}" | sed 's/;$//')"
    ;;
  marathon-24h)
    MASKING_RULES="mask-tenant-trace-resource|TRACES|REDACT_MASK|resource.attributes.tenant_id|##########|10|skip|true;mask-tenant-trace-span|TRACES|REDACT_MASK|scopeSpans[*].spans[*].attributes.tenant_id|##########|11|skip|true;mask-tenant-metrics-resource|METRICS|REDACT_MASK|resourcemetrics.resource.attributes.tenant_id|##########|10|skip|true;drop-tenant-logs|LOGS|DROP|resourcelogs.resource.attributes.tenant_id||1|skip|true"
    ;;
esac

log "Campaign configuration:"
log "  track=${TRACK} scenario=${SCENARIO} runs=${RUNS}"
log "  duration=${DURATION} workers=${WORKERS} rate=${RATE}"
log "  metrics_interval_sec=${METRICS_INTERVAL_SEC}"
log "  ssm_timeout=${SSM_TIMEOUT}"
log "  campaign_dir=${CAMPAIGN_DIR}"
log "  scenario_dir=${SCENARIO_DIR}"
if [[ -n "${MASKING_RULES_FILE}" ]]; then
  log "  masking_rules_file=${MASKING_RULES_FILE}"
fi

if [[ "${DRY_RUN}" == "true" ]]; then
  log "Dry run — exiting without execution"
  exit 0
fi

mkdir -p "${SCENARIO_DIR}"

# Resolve terraform outputs for deploy_via_ssm
DEPLOY_ARGS=()
DEPLOY_ARGS+=(--tf-dir "${TF_DIR}")
DEPLOY_ARGS+=(--duration "${DURATION}")
DEPLOY_ARGS+=(--workers "${WORKERS}")
DEPLOY_ARGS+=(--rate "${RATE}")
DEPLOY_ARGS+=(--metrics-interval-sec "${METRICS_INTERVAL_SEC}")
DEPLOY_ARGS+=(--ssm-timeout "${SSM_TIMEOUT}")

if [[ -n "${AWS_PROFILE}" ]]; then
  DEPLOY_ARGS+=(--aws-profile "${AWS_PROFILE}")
fi
if [[ -n "${GATEWAY_IMAGE}" ]]; then
  DEPLOY_ARGS+=(--gateway-image "${GATEWAY_IMAGE}")
fi
if [[ -n "${ARTIFACT_S3_URI}" ]]; then
  DEPLOY_ARGS+=(--artifact-s3-uri "${ARTIFACT_S3_URI}")
fi

campaign_failed="false"

for run_num in $(seq 1 "${RUNS}"); do
  run_dir="${SCENARIO_DIR}/run-$(printf '%03d' "${run_num}")"
  run_id="bench-${TRACK}-${SCENARIO}-$(printf '%03d' "${run_num}")-$(date +%Y%m%d_%H%M%S)"
  mkdir -p "${run_dir}"

  log "=== Run ${run_num}/${RUNS}: ${run_id} ==="

  if [[ "${SCENARIO}" == "otel-baseline" ]]; then
    # For baseline, use the dedicated baseline script
    # This requires SSH/SSM access to gateway node — delegate to deploy_via_ssm
    # with a special mode, or run otel-baseline.sh via SSM
    log "Running OTel Collector baseline via deploy_via_ssm (deploy-only) + otel-baseline.sh"

    # Deploy upstream only, then run baseline on gateway node
    "${REPO_ROOT}/infra/terraform/scripts/deploy_via_ssm.sh" \
      --run-id "${run_id}" \
      --deploy-only \
      "${DEPLOY_ARGS[@]}" || {
        log "FAIL: baseline upstream deploy failed for run ${run_num}"
        campaign_failed="true"
        break
      }

    log "Baseline deployed — metrics collection handled by otel-baseline.sh via SSM"
  else
    # Standard gateway benchmark via deploy_via_ssm
    # Override masking rules via environment
    GATEWAY_MASKING_RULES_OVERRIDE="${MASKING_RULES}" \
    "${REPO_ROOT}/infra/terraform/scripts/deploy_via_ssm.sh" \
      --run-id "${run_id}" \
      "${DEPLOY_ARGS[@]}" || {
        log "FAIL: run ${run_num} failed"
        campaign_failed="true"
        break
      }
  fi

  # Generate run metadata
  META_ARGS=(
    --run-id "${run_id}"
    --track "${TRACK}"
    --scenario "${SCENARIO}"
    --artifact-dir "${run_dir}"
    --duration "${DURATION}"
    --workers "${WORKERS}"
    --rate "${RATE}"
  )
  if [[ -n "${TF_DIR}" ]]; then
    META_ARGS+=(--tf-dir "${TF_DIR}")
  fi
  if [[ -n "${GATEWAY_IMAGE}" ]]; then
    META_ARGS+=(--gateway-image "${GATEWAY_IMAGE}")
  fi
  if [[ -n "${MASKING_RULES_FILE}" ]]; then
    META_ARGS+=(--masking-rules-file "${MASKING_RULES_FILE}")
  fi

  "${SCRIPT_DIR}/generate-run-meta.sh" "${META_ARGS[@]}" || log "WARNING: run-meta.json generation failed"

  # Calculate costs
  duration_minutes="$(echo "${DURATION}" | awk '{
    v=$1;
    if (v ~ /h$/) { sub(/h$/, "", v); printf "%.0f", v*60; }
    else if (v ~ /m$/) { sub(/m$/, "", v); printf "%.0f", v; }
    else if (v ~ /s$/) { sub(/s$/, "", v); printf "%.0f", v/60; }
    else { printf "%.0f", v; }
  }')"

  # Try to extract delta_accepted from finops-summary if available
  delta_accepted="0"
  finops_summary="/opt/finops/artifacts/${run_id}/finops-summary.txt"
  if [[ -f "${run_dir}/finops-summary.txt" ]]; then
    delta_accepted="$(awk -F= '/^delta_accepted=/ {print $2}' "${run_dir}/finops-summary.txt" || echo "0")"
  fi

  "${SCRIPT_DIR}/cost-calculator.sh" \
    --track "${TRACK}" \
    --duration-minutes "${duration_minutes}" \
    --delta-accepted "${delta_accepted:-0}" \
    --output-file "${run_dir}/cost-report.txt" || log "WARNING: cost calculation failed"

  log "Run ${run_num}/${RUNS} complete: ${run_dir}"
done

if [[ "${campaign_failed}" == "true" ]]; then
  log "Campaign FAILED — stopped early due to run failure"
  exit 1
fi

# Generate campaign report
log "Generating campaign report..."
"${SCRIPT_DIR}/report-generator.sh" --campaign-dir "${CAMPAIGN_DIR}" || log "WARNING: report generation failed"

log "Campaign complete: ${CAMPAIGN_DIR}"
