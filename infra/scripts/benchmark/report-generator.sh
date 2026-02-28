#!/usr/bin/env bash
set -euo pipefail

# Report generator for benchmark campaigns.
# Scans campaign directory for run artifacts and produces campaign-report.md + campaign-report.json.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<USAGE
Usage: $(basename "$0") --campaign-dir <path>

Options:
  --campaign-dir <path>   Root campaign directory (required)
  -h, --help              Show this help
USAGE
}

CAMPAIGN_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --campaign-dir) CAMPAIGN_DIR="$2"; shift 2 ;;
    -h|--help)      usage; exit 0 ;;
    *)              echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "${CAMPAIGN_DIR}" ]]; then
  echo "ERROR: --campaign-dir is required" >&2
  usage
  exit 1
fi

if [[ ! -d "${CAMPAIGN_DIR}" ]]; then
  echo "ERROR: campaign directory not found: ${CAMPAIGN_DIR}" >&2
  exit 1
fi

log() { printf '[report] %s\n' "$*"; }

read_key() {
  local file="$1"
  local key="$2"
  if [[ -f "${file}" ]]; then
    awk -F'=' -v k="${key}" '$1 == k { print $2; found=1; exit } END { if (!found) print "n/a" }' "${file}"
  else
    echo "n/a"
  fi
}

REPORT_MD="${CAMPAIGN_DIR}/campaign-report.md"
REPORT_JSON="${CAMPAIGN_DIR}/campaign-report.json"

# Collect all runs
runs_json="[]"

{
  echo "# Benchmark Campaign Report"
  echo ""
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Campaign directory: ${CAMPAIGN_DIR}"
  echo ""

  # Iterate tracks
  for track_dir in "${CAMPAIGN_DIR}"/c7i "${CAMPAIGN_DIR}"/c7g; do
    track="$(basename "${track_dir}")"
    [[ -d "${track_dir}" ]] || continue

    echo "## Track: ${track}"
    echo ""

    # Iterate scenarios
    for scenario_dir in "${track_dir}"/*/; do
      scenario="$(basename "${scenario_dir}")"
      [[ -d "${scenario_dir}" ]] || continue

      echo "### Scenario: ${scenario}"
      echo ""
      echo "| Run | Accepted | Dropped | Avg E2E (ms) | P99 E2E (ms) | Egress Savings (%) | Cost/1B (\$) |"
      echo "|-----|----------|---------|--------------|--------------|--------------------|-----------:|"

      run_count=0
      total_accepted=0
      total_dropped=0
      sum_avg_e2e="0"
      sum_p99_e2e="0"
      sum_cost_1b="0"
      valid_avg_count=0
      valid_p99_count=0
      valid_cost_count=0

      for run_dir in "${scenario_dir}"/run-*/; do
        [[ -d "${run_dir}" ]] || continue
        run_name="$(basename "${run_dir}")"
        run_count=$((run_count + 1))

        summary="${run_dir}/finops-summary.txt"
        cost_report="${run_dir}/cost-report.txt"
        run_meta="${run_dir}/run-meta.json"

        delta_accepted="$(read_key "${summary}" "delta_accepted")"
        delta_dropped="$(read_key "${summary}" "delta_dropped")"
        avg_e2e="$(read_key "${summary}" "avg_e2e_ms")"
        p99_e2e="$(read_key "${summary}" "p99_e2e_ms")"
        egress_savings="$(read_key "${summary}" "egress_savings_pct")"
        cost_1b="$(read_key "${cost_report}" "cost_per_1B_events_usd")"

        echo "| ${run_name} | ${delta_accepted} | ${delta_dropped} | ${avg_e2e} | ${p99_e2e} | ${egress_savings} | ${cost_1b} |"

        # Accumulate for averages
        if [[ "${delta_accepted}" != "n/a" && "${delta_accepted}" =~ ^[0-9]+$ ]]; then
          total_accepted=$((total_accepted + delta_accepted))
        fi
        if [[ "${delta_dropped}" != "n/a" && "${delta_dropped}" =~ ^[0-9]+$ ]]; then
          total_dropped=$((total_dropped + delta_dropped))
        fi
        if [[ "${avg_e2e}" != "n/a" && "${avg_e2e}" =~ ^[0-9.]+$ ]]; then
          sum_avg_e2e="$(awk -v a="${sum_avg_e2e}" -v b="${avg_e2e}" 'BEGIN { printf "%.6f", a+b }')"
          valid_avg_count=$((valid_avg_count + 1))
        fi
        if [[ "${p99_e2e}" != "n/a" && "${p99_e2e}" =~ ^[0-9.]+$ ]]; then
          sum_p99_e2e="$(awk -v a="${sum_p99_e2e}" -v b="${p99_e2e}" 'BEGIN { printf "%.6f", a+b }')"
          valid_p99_count=$((valid_p99_count + 1))
        fi
        if [[ "${cost_1b}" != "n/a" && "${cost_1b}" =~ ^[0-9.]+$ ]]; then
          sum_cost_1b="$(awk -v a="${sum_cost_1b}" -v b="${cost_1b}" 'BEGIN { printf "%.4f", a+b }')"
          valid_cost_count=$((valid_cost_count + 1))
        fi

        # Build run JSON entry
        run_json="$(jq -cn \
          --arg track "${track}" \
          --arg scenario "${scenario}" \
          --arg run "${run_name}" \
          --arg accepted "${delta_accepted}" \
          --arg dropped "${delta_dropped}" \
          --arg avg_e2e "${avg_e2e}" \
          --arg p99_e2e "${p99_e2e}" \
          --arg egress "${egress_savings}" \
          --arg cost "${cost_1b}" \
          '{track:$track, scenario:$scenario, run:$run, delta_accepted:$accepted, delta_dropped:$dropped, avg_e2e_ms:$avg_e2e, p99_e2e_ms:$p99_e2e, egress_savings_pct:$egress, cost_per_1B_usd:$cost}'
        )"
        runs_json="$(echo "${runs_json}" | jq --argjson entry "${run_json}" '. += [$entry]')"
      done

      # Summary row
      avg_of_avg="n/a"
      if [[ "${valid_avg_count}" -gt 0 ]]; then
        avg_of_avg="$(awk -v s="${sum_avg_e2e}" -v c="${valid_avg_count}" 'BEGIN { printf "%.6f", s/c }')"
      fi
      avg_of_p99="n/a"
      if [[ "${valid_p99_count}" -gt 0 ]]; then
        avg_of_p99="$(awk -v s="${sum_p99_e2e}" -v c="${valid_p99_count}" 'BEGIN { printf "%.6f", s/c }')"
      fi
      avg_cost="n/a"
      if [[ "${valid_cost_count}" -gt 0 ]]; then
        avg_cost="$(awk -v s="${sum_cost_1b}" -v c="${valid_cost_count}" 'BEGIN { printf "%.4f", s/c }')"
      fi

      echo ""
      echo "**Summary** (${run_count} runs): total_accepted=${total_accepted}, total_dropped=${total_dropped}, avg(avg_e2e)=${avg_of_avg}ms, avg(p99_e2e)=${avg_of_p99}ms, avg(cost/1B)=\$${avg_cost}"
      echo ""

      # P99/avg ratio
      if [[ "${avg_of_avg}" != "n/a" && "${avg_of_p99}" != "n/a" ]]; then
        ratio="$(awk -v p99="${avg_of_p99}" -v avg="${avg_of_avg}" 'BEGIN { if (avg > 0) printf "%.2f", p99/avg; else print "n/a" }')"
        echo "**Deterministic Latency Ratio** (p99/avg): ${ratio} (target: < 2.0)"
        echo ""
      fi
    done
  done

  # OTel Baseline comparison
  echo "## Efficiency Comparison"
  echo ""
  for track_dir in "${CAMPAIGN_DIR}"/c7i "${CAMPAIGN_DIR}"/c7g; do
    track="$(basename "${track_dir}")"
    [[ -d "${track_dir}" ]] || continue
    baseline_dir="${track_dir}/otel-baseline"
    [[ -d "${baseline_dir}" ]] || continue

    baseline_summary="${baseline_dir}/run-001/otel-baseline-summary.txt"
    if [[ -f "${baseline_summary}" ]]; then
      otel_epc="$(read_key "${baseline_summary}" "otel_events_per_core")"
      echo "### Track: ${track}"
      echo "- OTel Collector events/core: ${otel_epc}"
      echo "- (Compare with gateway runs to compute efficiency ratio)"
      echo ""
    fi
  done

} > "${REPORT_MD}"

# Write JSON report
echo "${runs_json}" | jq '{
  generated_utc: (now | strftime("%Y-%m-%dT%H:%M:%SZ")),
  runs: .
}' > "${REPORT_JSON}"

log "Campaign report: ${REPORT_MD}"
log "Campaign JSON:   ${REPORT_JSON}"
