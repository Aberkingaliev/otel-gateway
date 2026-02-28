#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${TF_DIR}/../.." && pwd)"

AWS_PROFILE=""
REGION_OVERRIDE=""
RUN_ID="bench-$(date +%Y%m%d_%H%M%S)"
DEPLOY_ONLY="false"
GATEWAY_IMAGE=""
REPO_URL="$(git -C "${REPO_ROOT}" config --get remote.origin.url 2>/dev/null || true)"
REPO_REF="$(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || echo "")"

DURATION="60m"
WORKERS="16"
RATE="15000"
METRICS_INTERVAL_SEC="10"
TENANT_ID="black_list"
ARTIFACT_S3_URI=""
TELEMETRYGEN_IMAGE="ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:latest"
MAX_INFLIGHT="8192"
EXPORTER_POOL_SIZE="64"
EXPORTER_IO_THREADS="0"
SSM_TIMEOUT="7200"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --tf-dir <path>               Terraform directory (default: infra/terraform)
  --aws-profile <name>          AWS CLI profile
  --region <aws-region>         Override region from terraform output
  --run-id <id>                 Benchmark run id (default: bench-YYYYmmdd_HHMMSS)
  --deploy-only                 Deploy upstream/gateway only, skip soak
  --gateway-image <image>       Prebuilt gateway image (if empty, build on gateway node from repo)
  --repo-url <url>              Repository URL for remote build mode
  --repo-ref <ref>              Git ref for remote build mode (commit/branch/tag)
  --duration <dur>              Soak duration for telemetrygen (default: 60m)
  --workers <n>                 Telemetrygen workers (default: 16)
  --rate <rps>                  Telemetrygen rate per signal (default: 15000)
  --metrics-interval-sec <n>    Metrics snapshot interval (default: 10)
  --tenant-id <id>              Tenant id used by workload (default: black_list)
  --artifact-s3-uri <s3://...>  Optional S3 prefix for artifact upload
  --telemetrygen-image <image>  Telemetrygen image
  --max-inflight <n>            Max inflight packets (default: 8192)
  --exporter-pool-size <n>      HTTP exporter connection pool size (default: 64)
  --exporter-io-threads <n>     HTTP exporter IO threads (0 = auto) (default: 0)
  --ssm-timeout <seconds>       SSM execution timeout in seconds (default: 7200)
  -h, --help                    Show this help
USAGE
}

log() {
  printf '[deploy-ssm] %s\n' "$*"
}

fail() {
  printf '[deploy-ssm] ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  local c="$1"
  command -v "$c" >/dev/null 2>&1 || fail "Required command not found: ${c}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tf-dir)
      TF_DIR="$2"
      shift 2
      ;;
    --aws-profile)
      AWS_PROFILE="$2"
      shift 2
      ;;
    --region)
      REGION_OVERRIDE="$2"
      shift 2
      ;;
    --run-id)
      RUN_ID="$2"
      shift 2
      ;;
    --deploy-only)
      DEPLOY_ONLY="true"
      shift
      ;;
    --gateway-image)
      GATEWAY_IMAGE="$2"
      shift 2
      ;;
    --repo-url)
      REPO_URL="$2"
      shift 2
      ;;
    --repo-ref)
      REPO_REF="$2"
      shift 2
      ;;
    --duration)
      DURATION="$2"
      shift 2
      ;;
    --workers)
      WORKERS="$2"
      shift 2
      ;;
    --rate)
      RATE="$2"
      shift 2
      ;;
    --metrics-interval-sec)
      METRICS_INTERVAL_SEC="$2"
      shift 2
      ;;
    --tenant-id)
      TENANT_ID="$2"
      shift 2
      ;;
    --artifact-s3-uri)
      ARTIFACT_S3_URI="$2"
      shift 2
      ;;
    --telemetrygen-image)
      TELEMETRYGEN_IMAGE="$2"
      shift 2
      ;;
    --max-inflight)
      MAX_INFLIGHT="$2"
      shift 2
      ;;
    --exporter-pool-size)
      EXPORTER_POOL_SIZE="$2"
      shift 2
      ;;
    --exporter-io-threads)
      EXPORTER_IO_THREADS="$2"
      shift 2
      ;;
    --ssm-timeout)
      SSM_TIMEOUT="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

require_cmd aws
require_cmd jq
require_cmd terraform

if [[ ! -d "${TF_DIR}" ]]; then
  fail "Terraform directory not found: ${TF_DIR}"
fi

AWS_ARGS=(aws)
if [[ -n "${AWS_PROFILE}" ]]; then
  AWS_ARGS+=(--profile "${AWS_PROFILE}")
fi

TF_OUTPUT_JSON="$(cd "${TF_DIR}" && terraform output -json)"
if [[ -z "${TF_OUTPUT_JSON}" || "${TF_OUTPUT_JSON}" == "{}" ]]; then
  fail "terraform output is empty. Run terraform apply first."
fi

REGION="${REGION_OVERRIDE}"
if [[ -z "${REGION}" ]]; then
  REGION="$(jq -r '.region.value // empty' <<<"${TF_OUTPUT_JSON}")"
fi
if [[ -z "${REGION}" ]]; then
  fail "Cannot resolve AWS region from terraform output and --region not provided."
fi
AWS_ARGS+=(--region "${REGION}")

GATEWAY_INSTANCE_ID="$(jq -r '.instance_ids.value.gateway // empty' <<<"${TF_OUTPUT_JSON}")"
UPSTREAM_INSTANCE_ID="$(jq -r '.instance_ids.value.upstream // empty' <<<"${TF_OUTPUT_JSON}")"
TELEMETRYGEN_INSTANCE_ID="$(jq -r '.instance_ids.value.telemetrygen // empty' <<<"${TF_OUTPUT_JSON}")"

GATEWAY_PRIVATE_IP="$(jq -r '.private_ips.value.gateway // empty' <<<"${TF_OUTPUT_JSON}")"
UPSTREAM_PRIVATE_IP="$(jq -r '.private_ips.value.upstream // empty' <<<"${TF_OUTPUT_JSON}")"
TELEMETRYGEN_PRIVATE_IP="$(jq -r '.private_ips.value.telemetrygen // empty' <<<"${TF_OUTPUT_JSON}")"

if [[ -z "${GATEWAY_INSTANCE_ID}" || -z "${UPSTREAM_INSTANCE_ID}" || -z "${TELEMETRYGEN_INSTANCE_ID}" ]]; then
  fail "terraform output missing one or more instance IDs"
fi
if [[ -z "${GATEWAY_PRIVATE_IP}" || -z "${UPSTREAM_PRIVATE_IP}" || -z "${TELEMETRYGEN_PRIVATE_IP}" ]]; then
  fail "terraform output missing one or more private IPs"
fi

log "Region: ${REGION}"
log "Run ID: ${RUN_ID}"
log "Instances: gateway=${GATEWAY_INSTANCE_ID}, upstream=${UPSTREAM_INSTANCE_ID}, telemetrygen=${TELEMETRYGEN_INSTANCE_ID}"
log "Private IPs: gateway=${GATEWAY_PRIVATE_IP}, upstream=${UPSTREAM_PRIVATE_IP}, telemetrygen=${TELEMETRYGEN_PRIVATE_IP}"

wait_ssm_online() {
  local instance_id="$1"
  local max_attempts="${2:-90}"
  local delay_sec="${3:-5}"

  for ((attempt=1; attempt<=max_attempts; attempt++)); do
    local status
    status="$("${AWS_ARGS[@]}" ssm describe-instance-information \
      --filters "Key=InstanceIds,Values=${instance_id}" \
      --query 'InstanceInformationList[0].PingStatus' \
      --output text 2>/dev/null || true)"

    if [[ "${status}" == "Online" ]]; then
      log "SSM online: ${instance_id}"
      return 0
    fi

    sleep "${delay_sec}"
  done

  fail "Instance did not become SSM online: ${instance_id}"
}

send_ssm_command() {
  local instance_id="$1"
  local comment="$2"
  local script_body="$3"

  local params_json
  params_json="$(jq -cn --arg cmd "${script_body}" --arg timeout "${SSM_TIMEOUT}" '{commands:[$cmd], executionTimeout:[$timeout]}')"

  local command_id
  command_id="$("${AWS_ARGS[@]}" ssm send-command \
    --instance-ids "${instance_id}" \
    --document-name "AWS-RunShellScript" \
    --comment "${comment}" \
    --parameters "${params_json}" \
    --query 'Command.CommandId' \
    --output text)"

  "${AWS_ARGS[@]}" ssm wait command-executed --command-id "${command_id}" --instance-id "${instance_id}" || true

  local status
  status="$("${AWS_ARGS[@]}" ssm get-command-invocation \
    --command-id "${command_id}" \
    --instance-id "${instance_id}" \
    --query 'Status' --output text)"

  local stdout
  local stderr
  stdout="$("${AWS_ARGS[@]}" ssm get-command-invocation --command-id "${command_id}" --instance-id "${instance_id}" --query 'StandardOutputContent' --output text || true)"
  stderr="$("${AWS_ARGS[@]}" ssm get-command-invocation --command-id "${command_id}" --instance-id "${instance_id}" --query 'StandardErrorContent' --output text || true)"

  if [[ -n "${stdout}" && "${stdout}" != "None" ]]; then
    printf '%s\n' "${stdout}"
  fi

  if [[ "${status}" != "Success" ]]; then
    if [[ -n "${stderr}" && "${stderr}" != "None" ]]; then
      printf '%s\n' "${stderr}" >&2
    fi
    fail "SSM command failed (${comment}) on ${instance_id} with status=${status}, command_id=${command_id}"
  fi
}

for iid in "${UPSTREAM_INSTANCE_ID}" "${GATEWAY_INSTANCE_ID}" "${TELEMETRYGEN_INSTANCE_ID}"; do
  wait_ssm_online "${iid}"
done

UPSTREAM_SETUP_SCRIPT="$(cat <<'EOS'
set -euo pipefail
mkdir -p /opt/finops/artifacts
cat >/opt/finops/upstream-config.yaml <<'YAML'
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:14328

processors:
  memory_limiter:
    check_interval: 1s
    limit_mib: 2048
    spike_limit_mib: 512
  batch:
    send_batch_size: 32768
    timeout: 200ms

exporters:
  debug:
    verbosity: basic

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [debug]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [debug]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [debug]
YAML

docker rm -f otel-upstream >/dev/null 2>&1 || true
docker run -d --name otel-upstream --restart unless-stopped \
  -p 14328:14328 \
  -v /opt/finops/upstream-config.yaml:/etc/otelcol-contrib/config.yaml:ro \
  otel/opentelemetry-collector-contrib:0.119.0 \
  --config=/etc/otelcol-contrib/config.yaml

echo "upstream_started=1"
EOS
)"

MASKING_RULES="mask-tenant-trace-resource|TRACES|REDACT_MASK|resource.attributes.tenant_id|##########|10|skip|true;mask-tenant-trace-span|TRACES|REDACT_MASK|scopeSpans[*].spans[*].attributes.tenant_id|##########|11|skip|true;mask-tenant-metrics-resource|METRICS|REDACT_MASK|resourcemetrics.resource.attributes.tenant_id|##########|10|skip|true;drop-tenant-logs|LOGS|DROP|resourcelogs.resource.attributes.tenant_id||1|skip|true"

GATEWAY_SETUP_SCRIPT="$(cat <<EOS
set -euo pipefail
mkdir -p /opt/finops/artifacts
UPSTREAM_IP='${UPSTREAM_PRIVATE_IP}'
GATEWAY_IMAGE_INPUT='${GATEWAY_IMAGE}'
REPO_URL_INPUT='${REPO_URL}'
REPO_REF_INPUT='${REPO_REF}'
MASKING_RULES='${MASKING_RULES}'
MAX_INFLIGHT='${MAX_INFLIGHT}'
EXPORTER_POOL_SIZE='${EXPORTER_POOL_SIZE}'
EXPORTER_IO_THREADS='${EXPORTER_IO_THREADS}'

if [[ -n "\${GATEWAY_IMAGE_INPUT}" ]]; then
  docker pull "\${GATEWAY_IMAGE_INPUT}" || true
  GATEWAY_IMAGE="\${GATEWAY_IMAGE_INPUT}"
else
  if [[ -z "\${REPO_URL_INPUT}" || -z "\${REPO_REF_INPUT}" ]]; then
    echo "repo url/ref required when --gateway-image is not provided" >&2
    exit 1
  fi

  if [[ ! -d /opt/finops/otel-gateway/.git ]]; then
    git clone "\${REPO_URL_INPUT}" /opt/finops/otel-gateway
  fi

  git -C /opt/finops/otel-gateway fetch --all --tags --prune
  git -C /opt/finops/otel-gateway checkout "\${REPO_REF_INPUT}"
  git -C /opt/finops/otel-gateway submodule update --init --recursive || true

  docker build -t finops-gateway:bench -f /opt/finops/otel-gateway/infra/docker/gateway.Dockerfile /opt/finops/otel-gateway
  GATEWAY_IMAGE="finops-gateway:bench"
fi

docker rm -f finops-gateway >/dev/null 2>&1 || true
docker run -d --name finops-gateway --restart unless-stopped \
  -p 4317:4317 \
  -p 4318:4318 \
  -p 9464:9464 \
  -e OTLP_UPSTREAM_TRACES_URL="http://\${UPSTREAM_IP}:14328/v1/traces" \
  -e OTLP_UPSTREAM_METRICS_URL="http://\${UPSTREAM_IP}:14328/v1/metrics" \
  -e OTLP_UPSTREAM_LOGS_URL="http://\${UPSTREAM_IP}:14328/v1/logs" \
  -e GATEWAY_QUEUE_ENABLED=true \
  -e GATEWAY_QUEUE_CAPACITY=65536 \
  -e GATEWAY_QUEUE_SHARDS=16 \
  -e GATEWAY_QUEUE_WORKERS=16 \
  -e GATEWAY_BACKPRESSURE_LOW=32768 \
  -e GATEWAY_BACKPRESSURE_HIGH=49152 \
  -e GATEWAY_BACKPRESSURE_CRITICAL=58982 \
	  -e GATEWAY_BACKPRESSURE_MAX_QUEUE_WAIT_MS=500 \
	  -e GATEWAY_BACKPRESSURE_SHED_LIGHT_RATIO=0.05 \
	  -e GATEWAY_BACKPRESSURE_SHED_AGGRESSIVE_RATIO=0.25 \
	  -e GATEWAY_ENABLE_REFRAME=true \
	  -e GATEWAY_REFRAME_INTEGRITY_MODE=none \
	  -e GATEWAY_MASKING_ENABLED=true \
  -e GATEWAY_MASKING_SIMD=on \
  -e GATEWAY_MASKING_MAX_OPS_PER_PACKET=128 \
  -e GATEWAY_MASKING_RULES="\${MASKING_RULES}" \
  -e GATEWAY_MAX_INFLIGHT="\${MAX_INFLIGHT}" \
  -e GATEWAY_EXPORTER_POOL_SIZE="\${EXPORTER_POOL_SIZE}" \
  -e GATEWAY_EXPORTER_IO_THREADS="\${EXPORTER_IO_THREADS}" \
  -e GATEWAY_METRICS_ENABLED=true \
  -e GATEWAY_METRICS_HTTP_ENABLED=true \
  -e GATEWAY_METRICS_HTTP_PORT=9464 \
  -e GATEWAY_METRICS_HTTP_PATH=/metrics \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=75 -Dio.netty.leakDetection.level=simple' \
  "\${GATEWAY_IMAGE}"

for i in \$(seq 1 120); do
  if curl -fsS http://127.0.0.1:9464/metrics >/dev/null 2>&1; then
    echo "gateway_ready=1"
    exit 0
  fi
  sleep 2
done

echo "gateway metrics endpoint is not ready" >&2
exit 1
EOS
)"

log "Deploy upstream node"
send_ssm_command "${UPSTREAM_INSTANCE_ID}" "deploy-upstream-${RUN_ID}" "${UPSTREAM_SETUP_SCRIPT}"

log "Deploy gateway node"
send_ssm_command "${GATEWAY_INSTANCE_ID}" "deploy-gateway-${RUN_ID}" "${GATEWAY_SETUP_SCRIPT}"

if [[ "${DEPLOY_ONLY}" == "true" ]]; then
  log "Deploy-only mode complete."
  exit 0
fi

TELEMETRYGEN_RUN_SCRIPT="$(cat <<EOS
set -euo pipefail
RUN_ID='${RUN_ID}'
GATEWAY_IP='${GATEWAY_PRIVATE_IP}'
DURATION='${DURATION}'
WORKERS='${WORKERS}'
RATE='${RATE}'
METRICS_INTERVAL='${METRICS_INTERVAL_SEC}'
TENANT_ID='${TENANT_ID}'
IMAGE='${TELEMETRYGEN_IMAGE}'
ARTIFACT_S3_URI='${ARTIFACT_S3_URI}'
ART_DIR="/opt/finops/artifacts/\${RUN_ID}"
METRICS_URL="http://\${GATEWAY_IP}:9464/metrics"
mkdir -p "\${ART_DIR}"

# quick smoke
now_s=\$(date +%s)
start_nano="\${now_s}000000000"
end_nano="\$((now_s + 1))000000000"
cat >"\${ART_DIR}/smoke-traces.json" <<JSON
{
  "resourceSpans": [{
    "resource": {"attributes": [
      {"key":"tenant_id","value":{"stringValue":"\${TENANT_ID}"}},
      {"key":"service.name","value":{"stringValue":"aws-ssm-smoke"}}
    ]},
    "scopeSpans": [{"scope": {"name": "smoke", "version": "1.0.0"}, "spans": [{
      "traceId": "5b8efff798038103d269b633813fc60c",
      "spanId": "eee19b7ec3c1b174",
      "name": "smoke-span",
      "kind": 2,
      "startTimeUnixNano": "\${start_nano}",
      "endTimeUnixNano": "\${end_nano}"
    }]}]
  }]
}
JSON

cat >"\${ART_DIR}/smoke-metrics.json" <<JSON
{
  "resourceMetrics": [{
    "resource": {"attributes": [
      {"key":"tenant_id","value":{"stringValue":"\${TENANT_ID}"}},
      {"key":"service.name","value":{"stringValue":"aws-ssm-smoke"}}
    ]},
    "scopeMetrics": [{"scope": {"name": "smoke", "version": "1.0.0"}, "metrics": [{
      "name": "aws_smoke_counter",
      "unit": "1",
      "sum": {
        "aggregationTemporality": 2,
        "isMonotonic": true,
        "dataPoints": [{"startTimeUnixNano": "\${start_nano}", "timeUnixNano": "\${end_nano}", "asInt": "1"}]
      }
    }]}]
  }]
}
JSON

cat >"\${ART_DIR}/smoke-logs.json" <<JSON
{
  "resourceLogs": [{
    "resource": {"attributes": [
      {"key":"tenant_id","value":{"stringValue":"\${TENANT_ID}"}},
      {"key":"service.name","value":{"stringValue":"aws-ssm-smoke"}}
    ]},
    "scopeLogs": [{"scope": {"name": "smoke", "version": "1.0.0"}, "logRecords": [{
      "timeUnixNano": "\${end_nano}",
      "severityNumber": 9,
      "severityText": "INFO",
      "body": {"stringValue": "smoke log"}
    }]}]
  }]
}
JSON

for s in traces metrics logs; do
  code=\$(curl -sS -o /dev/null -w "%{http_code}" -H "Content-Type: application/json" --data-binary @"\${ART_DIR}/smoke-\${s}.json" "http://\${GATEWAY_IP}:4318/v1/\${s}" || echo 000)
  echo "smoke_\${s}_http=\${code}" | tee -a "\${ART_DIR}/smoke.txt"
  [[ "\${code}" == "200" ]] || { echo "smoke failed for \${s}" >&2; exit 1; }
done

curl -fsS "\${METRICS_URL}" >"\${ART_DIR}/metrics-before.prom"

(
  while true; do
    printf "# ts=%s\\n" "\$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    curl -fsS "\${METRICS_URL}" || true
    printf "\\n"
    sleep "\${METRICS_INTERVAL}"
  done
) >"\${ART_DIR}/metrics.prom" 2>&1 &
metrics_pid=\$!

run_gen() {
  local sig="\$1"
  local path="/v1/\${sig}"
  docker run --rm "\${IMAGE}" "\${sig}" \
    --otlp-http \
    --otlp-insecure \
    --otlp-endpoint "\${GATEWAY_IP}:4318" \
    --otlp-http-url-path "\${path}" \
    --duration "\${DURATION}" \
    --workers "\${WORKERS}" \
    --rate "\${RATE}" \
    --service "aws-\${sig}" \
    --otlp-attributes "tenant_id=\\"\${TENANT_ID}\\"" \
    --telemetry-attributes "tenant_id=\\"\${TENANT_ID}\\"" \
    --telemetry-attributes "env=\\"soak\\""
}

run_gen traces >"\${ART_DIR}/telemetrygen-traces.log" 2>&1 & p1=\$!
run_gen metrics >"\${ART_DIR}/telemetrygen-metrics.log" 2>&1 & p2=\$!
run_gen logs >"\${ART_DIR}/telemetrygen-logs.log" 2>&1 & p3=\$!

set +e
wait \${p1}; rc1=\$?
wait \${p2}; rc2=\$?
wait \${p3}; rc3=\$?
set -e

kill \${metrics_pid} >/dev/null 2>&1 || true
wait \${metrics_pid} >/dev/null 2>&1 || true

curl -fsS "\${METRICS_URL}" >"\${ART_DIR}/metrics-after.prom"

metric_value() {
  local file="\$1"; local key="\$2"
  awk -v k="\$key" '\$0 ~ "^"k" " {print \$2; found=1; exit} END { if (!found) print 0 }' "\$file"
}

metric_packets_status_all() {
  local file="\$1"; local status="\$2"
  awk -v s="\$status" '
    /^gateway_packets_processed_total\{/ {
      if (\$0 ~ ("status=\"" s "\"") && \$0 ~ /signal="ALL"/) { print \$2; found=1; exit }
    }
    END { if (!found) print 0 }
  ' "\$file"
}

before_received=\$(metric_packets_status_all "\${ART_DIR}/metrics-before.prom" "received")
after_received=\$(metric_packets_status_all "\${ART_DIR}/metrics-after.prom" "received")
before_accepted=\$(metric_packets_status_all "\${ART_DIR}/metrics-before.prom" "accepted")
after_accepted=\$(metric_packets_status_all "\${ART_DIR}/metrics-after.prom" "accepted")
before_dropped=\$(metric_packets_status_all "\${ART_DIR}/metrics-before.prom" "dropped")
after_dropped=\$(metric_packets_status_all "\${ART_DIR}/metrics-after.prom" "dropped")

simd_active=\$(awk '/^gateway_mask_writer_active\{/{ if (\$0 ~ /active_writer="simd"/) { print \$2; found=1; exit } } END { if (!found) print 0 }' "\${ART_DIR}/metrics-after.prom")
simd_available=\$(metric_value "\${ART_DIR}/metrics-after.prom" "gateway_masking_simd_available")
simd_strict=\$(metric_value "\${ART_DIR}/metrics-after.prom" "gateway_masking_simd_strict_mode")

delta_received=\$((after_received - before_received))
delta_accepted=\$((after_accepted - before_accepted))
delta_dropped=\$((after_dropped - before_dropped))

{
  echo "telemetrygen_exit_codes traces=\${rc1} metrics=\${rc2} logs=\${rc3}"
  echo "delta_received=\${delta_received}"
  echo "delta_accepted=\${delta_accepted}"
  echo "delta_dropped=\${delta_dropped}"
  echo "simd_active_metric=\${simd_active}"
  echo "simd_available_metric=\${simd_available}"
  echo "simd_strict_metric=\${simd_strict}"
  echo "artifact_dir=\${ART_DIR}"
} | tee "\${ART_DIR}/finops-summary.txt"

if [[ "\${rc1}" -ne 0 || "\${rc2}" -ne 0 || "\${rc3}" -ne 0 ]]; then
  echo "telemetrygen failed" >&2
  exit 1
fi
if [[ "\${simd_active}" != "1" || "\${simd_strict}" != "1" ]]; then
  echo "SIMD strict gate failed" >&2
  exit 1
fi
if [[ "\${delta_dropped}" -le 0 ]]; then
  echo "expected logs DROP to produce positive dropped delta" >&2
  exit 1
fi

if [[ -n "\${ARTIFACT_S3_URI}" ]]; then
  aws s3 cp --recursive "\${ART_DIR}" "\${ARTIFACT_S3_URI%/}/\${RUN_ID}/telemetrygen/"
  echo "artifact_s3=\${ARTIFACT_S3_URI%/}/\${RUN_ID}/telemetrygen/" | tee -a "\${ART_DIR}/finops-summary.txt"
fi
EOS
)"

GATEWAY_COLLECT_SCRIPT="$(cat <<EOS
set -euo pipefail
RUN_ID='${RUN_ID}'
ARTIFACT_S3_URI='${ARTIFACT_S3_URI}'
ART_DIR="/opt/finops/artifacts/\${RUN_ID}"
mkdir -p "\${ART_DIR}"
docker logs finops-gateway >"\${ART_DIR}/gateway.log" 2>&1 || true
curl -fsS http://127.0.0.1:9464/metrics >"\${ART_DIR}/gateway-metrics-latest.prom" || true
if [[ -n "\${ARTIFACT_S3_URI}" ]]; then
  aws s3 cp "\${ART_DIR}/gateway.log" "\${ARTIFACT_S3_URI%/}/\${RUN_ID}/gateway/gateway.log" || true
  aws s3 cp "\${ART_DIR}/gateway-metrics-latest.prom" "\${ARTIFACT_S3_URI%/}/\${RUN_ID}/gateway/gateway-metrics-latest.prom" || true
fi
echo "gateway_artifact_dir=\${ART_DIR}"
EOS
)"

UPSTREAM_COLLECT_SCRIPT="$(cat <<EOS
set -euo pipefail
RUN_ID='${RUN_ID}'
ARTIFACT_S3_URI='${ARTIFACT_S3_URI}'
ART_DIR="/opt/finops/artifacts/\${RUN_ID}"
mkdir -p "\${ART_DIR}"
docker logs otel-upstream >"\${ART_DIR}/upstream.log" 2>&1 || true
if [[ -n "\${ARTIFACT_S3_URI}" ]]; then
  aws s3 cp "\${ART_DIR}/upstream.log" "\${ARTIFACT_S3_URI%/}/\${RUN_ID}/upstream/upstream.log" || true
fi
echo "upstream_artifact_dir=\${ART_DIR}"
EOS
)"

log "Run telemetrygen soak from telemetrygen node"
send_ssm_command "${TELEMETRYGEN_INSTANCE_ID}" "run-soak-${RUN_ID}" "${TELEMETRYGEN_RUN_SCRIPT}"

log "Collect gateway artifacts"
send_ssm_command "${GATEWAY_INSTANCE_ID}" "collect-gateway-${RUN_ID}" "${GATEWAY_COLLECT_SCRIPT}"

log "Collect upstream artifacts"
send_ssm_command "${UPSTREAM_INSTANCE_ID}" "collect-upstream-${RUN_ID}" "${UPSTREAM_COLLECT_SCRIPT}"

log "Benchmark orchestration complete"
if [[ -n "${ARTIFACT_S3_URI}" ]]; then
  log "Artifacts uploaded under: ${ARTIFACT_S3_URI%/}/${RUN_ID}/"
else
  log "Artifacts on instances under: /opt/finops/artifacts/${RUN_ID}"
fi
