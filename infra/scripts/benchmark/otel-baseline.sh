#!/usr/bin/env bash
set -euo pipefail

# OTel Collector baseline benchmark — control group for efficiency comparison.
# Deploys a vanilla OTel Collector on the gateway node (replacing the gateway)
# with the same upstream + telemetrygen workload, then collects CPU and throughput
# metrics for comparison.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

UPSTREAM_IP="${1:?Usage: otel-baseline.sh <upstream_ip> <gateway_ip> <duration> <workers> <rate> <artifact_dir> [telemetrygen_image]}"
GATEWAY_IP="${2:?missing gateway_ip}"
DURATION="${3:-60m}"
WORKERS="${4:-16}"
RATE="${5:-15000}"
ARTIFACT_DIR="${6:?missing artifact_dir}"
TELEMETRYGEN_IMAGE="${7:-ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:latest}"
OTEL_COLLECTOR_IMAGE="${OTEL_COLLECTOR_IMAGE:-otel/opentelemetry-collector-contrib:0.119.0}"
METRICS_INTERVAL_SEC="${METRICS_INTERVAL_SEC:-10}"

mkdir -p "${ARTIFACT_DIR}"

log() { printf '[otel-baseline] %s\n' "$*"; }

log "Deploying OTel Collector baseline on gateway node"

# Generate collector config: otlp receiver → batch → otlp/upstream exporter
cat >"${ARTIFACT_DIR}/otel-baseline-config.yaml" <<YAML
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  batch:
    send_batch_size: 32768
    timeout: 200ms

exporters:
  otlphttp:
    endpoint: http://${UPSTREAM_IP}:14328
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp]
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp]
    logs:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp]
YAML

# Stop any existing gateway/collector on the gateway node
docker rm -f finops-gateway otel-baseline >/dev/null 2>&1 || true

# Start OTel Collector
docker run -d --name otel-baseline --restart unless-stopped \
  -p 4317:4317 \
  -p 4318:4318 \
  -v "${ARTIFACT_DIR}/otel-baseline-config.yaml:/etc/otelcol-contrib/config.yaml:ro" \
  "${OTEL_COLLECTOR_IMAGE}" \
  --config=/etc/otelcol-contrib/config.yaml

# Wait for readiness
log "Waiting for OTel Collector readiness..."
for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:4318/v1/traces" -X POST -H "Content-Type: application/json" -d '{}' >/dev/null 2>&1; then
    log "OTel Collector ready"
    break
  fi
  if [[ "$i" -eq 60 ]]; then
    log "ERROR: OTel Collector not ready after 60s"
    exit 1
  fi
  sleep 1
done

# Detect CPU count
NUM_CORES="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"
log "Detected ${NUM_CORES} CPU cores"

# Background CPU monitoring via docker stats
(
  while true; do
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    cpu_pct="$(docker stats --no-stream --format '{{.CPUPerc}}' otel-baseline 2>/dev/null | tr -d '%' || echo "0")"
    printf "%s cpu_pct=%s\n" "${ts}" "${cpu_pct}"
    sleep "${METRICS_INTERVAL_SEC}"
  done
) >"${ARTIFACT_DIR}/otel-baseline-cpu.log" 2>&1 &
cpu_monitor_pid=$!

# Run telemetrygen
run_gen() {
  local sig="$1"
  docker run --rm "${TELEMETRYGEN_IMAGE}" "${sig}" \
    --otlp-http \
    --otlp-insecure \
    --otlp-endpoint "${GATEWAY_IP}:4318" \
    --otlp-http-url-path "/v1/${sig}" \
    --duration "${DURATION}" \
    --workers "${WORKERS}" \
    --rate "${RATE}" \
    --service "baseline-${sig}" \
    --otlp-attributes "tenant_id=\"baseline\"" \
    --telemetry-attributes "tenant_id=\"baseline\""
}

log "Running telemetrygen workload (${DURATION}, ${WORKERS} workers, ${RATE} rps per signal)"
run_gen traces >"${ARTIFACT_DIR}/telemetrygen-traces.log" 2>&1 & p1=$!
run_gen metrics >"${ARTIFACT_DIR}/telemetrygen-metrics.log" 2>&1 & p2=$!
run_gen logs >"${ARTIFACT_DIR}/telemetrygen-logs.log" 2>&1 & p3=$!

set +e
wait ${p1}; rc1=$?
wait ${p2}; rc2=$?
wait ${p3}; rc3=$?
set -e

# Stop CPU monitor
kill ${cpu_monitor_pid} >/dev/null 2>&1 || true
wait ${cpu_monitor_pid} >/dev/null 2>&1 || true

# Collect logs
docker logs otel-baseline >"${ARTIFACT_DIR}/otel-baseline.log" 2>&1 || true

# Calculate average CPU
avg_cpu_pct="$(awk -F'cpu_pct=' '
  NF==2 { sum+=$2; count++ }
  END { if (count>0) printf "%.2f", sum/count; else print "0" }
' "${ARTIFACT_DIR}/otel-baseline-cpu.log")"

# Estimate events processed (3 signals × rate × workers × duration_seconds)
duration_seconds="$(echo "${DURATION}" | awk '{
  v=$1;
  if (v ~ /h$/) { sub(/h$/, "", v); printf "%.0f", v*3600; }
  else if (v ~ /m$/) { sub(/m$/, "", v); printf "%.0f", v*60; }
  else if (v ~ /s$/) { sub(/s$/, "", v); printf "%.0f", v; }
  else { printf "%.0f", v*60; }
}')"
estimated_events=$(( RATE * WORKERS * duration_seconds * 3 ))

# Efficiency calculation
otel_events_per_core="$(awk -v events="${estimated_events}" -v cpu="${avg_cpu_pct}" -v cores="${NUM_CORES}" '
  BEGIN {
    if (cpu > 0 && cores > 0) {
      used_cores = (cpu / 100.0) * cores;
      if (used_cores > 0) {
        eps = events / used_cores;
        printf "%.0f", eps;
      } else {
        print 0;
      }
    } else {
      print 0;
    }
  }
')"

{
  echo "# OTel Collector Baseline Summary"
  echo "otel_collector_image=${OTEL_COLLECTOR_IMAGE}"
  echo "duration=${DURATION}"
  echo "workers=${WORKERS}"
  echo "rate=${RATE}"
  echo "num_cores=${NUM_CORES}"
  echo "telemetrygen_exit_codes traces=${rc1} metrics=${rc2} logs=${rc3}"
  echo "estimated_events=${estimated_events}"
  echo "avg_cpu_pct=${avg_cpu_pct}"
  echo "otel_events_per_core=${otel_events_per_core}"
  echo "artifact_dir=${ARTIFACT_DIR}"
} | tee "${ARTIFACT_DIR}/otel-baseline-summary.txt"

# Cleanup
docker rm -f otel-baseline >/dev/null 2>&1 || true

if [[ "${rc1}" -ne 0 || "${rc2}" -ne 0 || "${rc3}" -ne 0 ]]; then
  log "WARNING: telemetrygen had non-zero exit codes: traces=${rc1} metrics=${rc2} logs=${rc3}"
fi

log "Baseline benchmark complete"
