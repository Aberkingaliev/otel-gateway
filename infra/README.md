# Infra: Build and Production-Like Runbook

This folder contains everything needed to build and run the OTEL gateway stack with Docker Compose, run pre-production validation, and execute AWS benchmark campaigns.

## What is included

- `docker-compose.yml`: gateway + upstream OTel Collector stack
- `docker/gateway.Dockerfile`: gateway image build
- `docker/gateway-entrypoint.sh`: starts proxy (gRPC `:4317`, HTTP `:4318`)
- `otelcol/config.yaml`: upstream collector config
- `scripts/`: grouped entrypoints — see `scripts/README.md`
- `env/`: masking rule profiles for benchmark scenarios
- `.env.example`: environment template
- `.env.preprod.example`: strict SIMD + high-RPS pre-prod profile
- `PREPROD_CONCURRENCY_AUDIT_CHECKLIST.md`: ownership/concurrency checklist
- `AWS_BENCHMARK_V2.md`: AWS benchmark campaign protocol (4 KPIs, 5 scenarios)
- `terraform/`: AWS 3-node benchmark provisioning

## Quick start

```bash
cp infra/.env.example infra/.env
./infra/scripts/stack/up.sh
./infra/scripts/smoke/valid-otlp.sh
./infra/scripts/stack/logs.sh
./infra/scripts/stack/down.sh
```

## Masking verification

```bash
./infra/scripts/smoke/masking-explicit.sh 127.0.0.1 4318 traces
```

Strict payload assertion (requires detailed upstream payload logs):

```bash
MASKING_EXPLICIT_STRICT=true ./infra/scripts/smoke/masking-explicit.sh 127.0.0.1 4318 traces
```

## Pre-prod profile

```bash
cp infra/.env.preprod.example infra/.env.preprod
./infra/scripts/stack/up-preprod.sh
```

## Load / soak testing

Telemetrygen soak with artifact capture:

```bash
./infra/scripts/soak/telemetrygen.sh 60m 16 15000 10
```

FinOps realistic profile (strict SIMD + masking + DROP logs + p99/egress metrics):

```bash
./infra/scripts/soak/finops-realistic.sh 10m 16 15000 10
```

Valid OTLP parallel load (30k requests, concurrency 128):

```bash
./infra/scripts/soak/valid-otlp.sh 127.0.0.1 4318 30000 128 all
```

Notes:
- Default tenant: `tenant_id="black_list"`. Override: `SOAK_TENANT_ID=TENANT1234`
- Upstream log capture disabled by default. Enable: `CAPTURE_UPSTREAM_LOGS=true`
- Artifacts written to `infra/artifacts/soak-<timestamp>/`

## Pre-prod hardening

3-stage protocol (warmup 15m / measure 60m / cooldown 5m) + go/no-go gate:

```bash
./infra/scripts/preprod/hardening-run.sh
```

Outputs:
- `infra/artifacts/preprod-hardening-<timestamp>/go-no-go.txt`
- `infra/artifacts/preprod-hardening-<timestamp>/measure/bottleneck-topn.txt`

Go/no-go gates: SIMD strict, leaks=0, avg_e2e < 2ms, p99_e2e < 1ms, memory growth < 5%.

## AWS benchmark campaign

See `AWS_BENCHMARK_V2.md` for the full protocol (4 KPIs, 5 scenarios, PASS gates).

```bash
# Single scenario
./infra/scripts/benchmark/run-campaign.sh --track c7i --scenario peak-rps --runs 5

# Cost calculator
./infra/scripts/benchmark/cost-calculator.sh --track c7i --duration-minutes 60 --delta-accepted 1000000
```

Scenarios: `peak-rps`, `security-wall`, `finops-stress`, `otel-baseline`, `marathon-24h`.

Masking profiles:
- `env/masking-security-wall.rules` — 12 wildcard-heavy rules (SIMD stress test)
- `env/masking-finops-stress.rules` — 4 rules with DROP LOGS (~33% egress savings)

## Environment knobs

All keys defined in `GatewayEnvKeys.java`. Set in `infra/.env`:

**Upstream endpoints:**
- `OTLP_UPSTREAM_TRACES_URL`, `OTLP_UPSTREAM_METRICS_URL`, `OTLP_UPSTREAM_LOGS_URL`

**Queue + backpressure:**
- `GATEWAY_QUEUE_ENABLED`, `GATEWAY_QUEUE_CAPACITY`, `GATEWAY_QUEUE_SHARDS`, `GATEWAY_QUEUE_WORKERS`
- `GATEWAY_BACKPRESSURE_LOW`, `GATEWAY_BACKPRESSURE_HIGH`, `GATEWAY_BACKPRESSURE_CRITICAL`
- `GATEWAY_BACKPRESSURE_MAX_QUEUE_WAIT_MS`, `GATEWAY_BACKPRESSURE_SHED_LIGHT_RATIO`, `GATEWAY_BACKPRESSURE_SHED_AGGRESSIVE_RATIO`

**Exporter pool:**
- `GATEWAY_MAX_INFLIGHT` (default: 8192)
- `GATEWAY_EXPORTER_POOL_SIZE` (default: 64)
- `GATEWAY_EXPORTER_IO_THREADS` (default: 0 = auto)

**Pipeline:**
- `GATEWAY_ENABLE_REFRAME`, `GATEWAY_REFRAME_INTEGRITY_MODE` (`none|crc32_tail_le|crc32_tail_be`)
- `GATEWAY_HEALTHCHECK_PATH`

**Masking:**
- `GATEWAY_MASKING_ENABLED`, `GATEWAY_MASKING_SIMD` (`on|auto|off`), `GATEWAY_MASKING_MAX_OPS_PER_PACKET`
- `GATEWAY_MASKING_RULES` (inline rules, `;`-separated)

**Audit:**
- `GATEWAY_AUDIT_ENABLED`, `GATEWAY_AUDIT_DIR`, `GATEWAY_AUDIT_QUEUE_CAPACITY`, etc.

**Metrics:**
- `GATEWAY_METRICS_ENABLED`, `GATEWAY_METRICS_LOG_INTERVAL_SEC`
- `GATEWAY_METRICS_HTTP_ENABLED`, `GATEWAY_METRICS_HTTP_PORT`, `GATEWAY_METRICS_HTTP_PATH`

**JVM:**
- `JAVA_TOOL_OPTIONS` (default: `-XX:MaxRAMPercentage=75 -Dio.netty.leakDetection.level=simple`)

## Masking rules format

```
ruleId|signal|action|sourcePath|redactionToken|priority|onMismatch|enabled
```

- `signal`: `TRACES|METRICS|LOGS|ALL`
- `action`: `DROP|REDACT_MASK`
- `onMismatch`: `skip` (fail-open) or `fail_closed` (drop packet)
- For `REDACT_MASK`, redaction token byte length must match value byte length

## Prometheus metrics

```bash
curl -s http://127.0.0.1:9464/metrics
```

Key metrics:
- `gateway_packets_processed_total{signal,status}` — received/accepted/dropped counters
- `gateway_queue_depth` — current queue depth
- `gateway_end_to_end_duration_nanos_sum` / `_count` — latency summary
- `gateway_end_to_end_p99_nanos` — p99 end-to-end latency (nanoseconds)
- `gateway_mask_writer_active{requested_mode,active_writer}` — SIMD/scalar selection
- `gateway_masking_simd_available` / `gateway_masking_simd_strict_mode` — SIMD status

## Build only

```bash
./infra/scripts/stack/build.sh
```
