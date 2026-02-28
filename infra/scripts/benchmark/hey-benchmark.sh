#!/usr/bin/env bash
set -euo pipefail
#
# High-throughput OTLP benchmark using hey + pre-generated protobuf payload.
# Runs on the telemetrygen EC2 node via SSM.
#
# Usage:
#   hey-benchmark.sh --gateway-ip <private-ip> --duration 60m \
#                    --concurrency 100 --spans-per-request 100
#
# Prerequisites on node: hey, python3 (for payload generation)

GATEWAY_IP=""
DURATION="60m"
CONCURRENCY=100
SPANS_PER_REQUEST=100
SIGNAL="traces"
ARTIFACT_DIR="/opt/finops/artifacts/hey-benchmark"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --gateway-ip <ip>          Gateway private IP (required)
  --duration <dur>           Benchmark duration (default: 60m)
  --concurrency <n>          Concurrent connections (default: 100)
  --spans-per-request <n>    Spans per OTLP request (default: 100)
  --signal <traces|metrics|logs>  Signal type (default: traces)
  --artifact-dir <path>      Output directory (default: /opt/finops/artifacts/hey-benchmark)
  -h, --help                 Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gateway-ip)       GATEWAY_IP="$2"; shift 2 ;;
    --duration)         DURATION="$2"; shift 2 ;;
    --concurrency)      CONCURRENCY="$2"; shift 2 ;;
    --spans-per-request) SPANS_PER_REQUEST="$2"; shift 2 ;;
    --signal)           SIGNAL="$2"; shift 2 ;;
    --artifact-dir)     ARTIFACT_DIR="$2"; shift 2 ;;
    -h|--help)          usage; exit 0 ;;
    *)                  echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

[[ -n "${GATEWAY_IP}" ]] || { echo "ERROR: --gateway-ip required" >&2; exit 1; }

mkdir -p "${ARTIFACT_DIR}"

# Install hey if not present
if ! command -v hey >/dev/null 2>&1; then
  echo "Installing hey..."
  ARCH="$(uname -m)"
  case "${ARCH}" in
    x86_64)  HEY_ARCH="linux_amd64" ;;
    aarch64) HEY_ARCH="linux_arm64" ;;
    *)       echo "Unsupported arch: ${ARCH}" >&2; exit 1 ;;
  esac
  curl -fsSL "https://hey-release.s3.us-east-2.amazonaws.com/hey_${HEY_ARCH}" -o /usr/local/bin/hey
  chmod +x /usr/local/bin/hey
fi

# Generate protobuf payload
echo "Generating OTLP ${SIGNAL} payload with ${SPANS_PER_REQUEST} spans..."
cat > /tmp/gen-payload.py << 'PYEOF'
import struct, sys, os, time, random

def encode_varint(v):
    r = bytearray()
    while v > 0x7F:
        r.append((v & 0x7F) | 0x80); v >>= 7
    r.append(v & 0x7F)
    return bytes(r)

def field_varint(fn, v): return encode_varint((fn << 3) | 0) + encode_varint(v)
def field_fixed64(fn, v): return encode_varint((fn << 3) | 1) + struct.pack('<Q', v)
def field_bytes(fn, d):
    return encode_varint((fn << 3) | 2) + encode_varint(len(d)) + d
def field_string(fn, s): return field_bytes(fn, s.encode('utf-8'))

def any_str(s): return field_string(1, s)
def any_int(n): return field_varint(2, n)
def kv(k, v): return field_string(1, k) + field_bytes(2, v)

def make_span(i, tid):
    b = bytearray()
    b += field_bytes(1, tid)
    b += field_bytes(2, os.urandom(8))
    ops = ['GET /api/v1/users', 'POST /api/v1/orders', 'DB SELECT',
           'redis GET', 'grpc.call', 'http.request', 'queue.pub',
           'cache.get', 'auth.check', 'pay.process']
    b += field_string(5, ops[i % len(ops)])
    b += field_varint(6, 3)
    now = int(time.time() * 1e9)
    b += field_fixed64(7, now - random.randint(100000, 5000000))
    b += field_fixed64(8, now)
    for a in [kv('tenant_id', any_str('black_list')),
              kv('env', any_str('soak')),
              kv('http.method', any_str('GET')),
              kv('http.status_code', any_int(200)),
              kv('http.url', any_str(f'https://api.example.com/v1/r/{i}')),
              kv('user.id', any_str(f'u-{i%1000:04d}')),
              kv('user.email', any_str(f'u{i%1000}@ex.com')),
              kv('net.peer.ip', any_str(f'10.0.{i%256}.{(i*7)%256}'))]:
        b += field_bytes(9, a)
    b += field_bytes(15, field_varint(1, 1))
    return bytes(b)

n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
tid = os.urandom(16)
scope = field_string(1, 'com.acme.gateway') + field_string(2, '1.0')
spans = bytearray()
for i in range(n):
    spans += field_bytes(2, make_span(i, tid))
ss = field_bytes(1, scope) + bytes(spans)
res_attrs = bytearray()
for a in [kv('service.name', any_str('payment-svc')),
          kv('tenant_id', any_str('black_list')),
          kv('deployment.environment', any_str('production'))]:
    res_attrs += field_bytes(1, a)
rs = field_bytes(1, bytes(res_attrs)) + field_bytes(2, ss)
payload = field_bytes(1, rs)
sys.stdout.buffer.write(payload)
print(f'{n} spans, {len(payload)} bytes', file=sys.stderr)
PYEOF

python3 /tmp/gen-payload.py "${SPANS_PER_REQUEST}" > "${ARTIFACT_DIR}/payload.bin" 2>"${ARTIFACT_DIR}/payload-info.txt"
cat "${ARTIFACT_DIR}/payload-info.txt"
PAYLOAD_SIZE=$(stat -c%s "${ARTIFACT_DIR}/payload.bin" 2>/dev/null || stat -f%z "${ARTIFACT_DIR}/payload.bin")
echo "Payload size: ${PAYLOAD_SIZE} bytes"

# Convert duration to seconds for hey (hey uses -z for duration)
URL="http://${GATEWAY_IP}:4318/v1/${SIGNAL}"

echo ""
echo "=== Starting hey benchmark ==="
echo "URL: ${URL}"
echo "Duration: ${DURATION}"
echo "Concurrency: ${CONCURRENCY}"
echo "Spans per request: ${SPANS_PER_REQUEST}"
echo ""

# Collect metrics before
curl -fsS "http://${GATEWAY_IP}:9464/metrics" > "${ARTIFACT_DIR}/metrics-before.prom" 2>/dev/null || true

# Run hey
hey -z "${DURATION}" \
    -c "${CONCURRENCY}" \
    -m POST \
    -D "${ARTIFACT_DIR}/payload.bin" \
    -T "application/x-protobuf" \
    -disable-keepalive=false \
    "${URL}" \
    2>&1 | tee "${ARTIFACT_DIR}/hey-output.txt"

# Collect metrics after
curl -fsS "http://${GATEWAY_IP}:9464/metrics" > "${ARTIFACT_DIR}/metrics-after.prom" 2>/dev/null || true

# Parse hey output for summary
echo ""
echo "=== Summary ==="
total_requests=$(grep -oP 'Total:\s+[\d.]+' "${ARTIFACT_DIR}/hey-output.txt" | head -1 || echo "n/a")
rps=$(grep -oP 'Requests/sec:\s+[\d.]+' "${ARTIFACT_DIR}/hey-output.txt" || echo "n/a")
avg_latency=$(grep -oP 'Average:\s+[\d.]+' "${ARTIFACT_DIR}/hey-output.txt" | head -1 || echo "n/a")
p99_latency=$(grep -oP '99%.*?[\d.]+ secs' "${ARTIFACT_DIR}/hey-output.txt" || echo "n/a")

echo "rps=${rps}"
echo "spans_per_sec=$(python3 -c "print(${rps##*:} * ${SPANS_PER_REQUEST})" 2>/dev/null || echo n/a)"
echo "avg_latency=${avg_latency}"
echo "p99_latency=${p99_latency}"
echo ""

# Gateway delta
metric_val() {
  awk -v k="$2" '$0 ~ "^"k" " {print $2; exit}' "$1"
}
pkt_status() {
  awk -v s="$1" '/^gateway_packets_processed_total\{/ { if ($0 ~ ("status=\"" s "\"") && $0 ~ /signal="ALL"/) {print $2; exit} }' "$2"
}

before_recv=$(pkt_status "received" "${ARTIFACT_DIR}/metrics-before.prom")
after_recv=$(pkt_status "received" "${ARTIFACT_DIR}/metrics-after.prom")
before_acc=$(pkt_status "accepted" "${ARTIFACT_DIR}/metrics-before.prom")
after_acc=$(pkt_status "accepted" "${ARTIFACT_DIR}/metrics-after.prom")
delta_recv=$(( ${after_recv:-0} - ${before_recv:-0} ))
delta_acc=$(( ${after_acc:-0} - ${before_acc:-0} ))

echo "delta_received=${delta_recv}"
echo "delta_accepted=${delta_acc}"
echo "total_spans_processed=$(( delta_recv * SPANS_PER_REQUEST ))"

# Gateway p99
p99_gw=$(awk '/^gateway_end_to_end_p99_nanos / {print $2}' "${ARTIFACT_DIR}/metrics-after.prom")
echo "gateway_p99_nanos=${p99_gw}"
echo "gateway_p99_ms=$(python3 -c "print(f'{${p99_gw:-0}/1e6:.3f}')")"
