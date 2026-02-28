# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

High-performance OTLP proxy gateway (Java/Netty) that accepts gRPC (:4317) and HTTP (:4318) telemetry traffic, applies policy-driven mutations (drop/mask/reframe) at the protobuf wire level without deserializing into objects, and forwards to upstream OTLP backends asynchronously.

## Build & Test Commands

```bash
# Run all tests (from gateway/)
cd gateway && ./gradlew test

# Run a single test class
./gradlew test --tests 'com.acme.finops.gateway.wire.mutate.MutationPlanValidatorTest'

# Build only (no tests)
./gradlew classes

# Coverage report (branch coverage >= 80% enforced)
./gradlew jacocoTestReport

# Full check (tests + coverage verification)
./gradlew check

# CI verification tasks
./gradlew runCompatCheck
./gradlew runConformanceHarness
./gradlew runPerfRegressionCheck
```

## Docker Stack (from repo root)

```bash
cp infra/.env.example infra/.env
./infra/scripts/stack/up.sh          # build + start gateway + upstream collector
./infra/scripts/smoke/valid-otlp.sh  # functional smoke (preferred)
./infra/scripts/smoke/grpc.sh        # gRPC smoke
./infra/scripts/smoke/masking-explicit.sh 127.0.0.1 4318 traces  # masking verification
./infra/scripts/stack/logs.sh        # tail logs
./infra/scripts/stack/down.sh        # stop
```

Soak / load testing:
```bash
./infra/scripts/soak/telemetrygen.sh 60m 16 15000 10
./infra/scripts/soak/finops-realistic.sh 10m 16 15000 10
```

Pre-prod hardening (warmup 15m / measure 60m / cooldown 5m + go/no-go gate):
```bash
./infra/scripts/preprod/hardening-run.sh
```

## AWS Benchmark Campaign

```bash
# Deploy infra
cd infra/terraform && terraform apply -var-file=profile_c7i.tfvars

# Run benchmark scenarios
./infra/scripts/benchmark/run-campaign.sh --track c7i --scenario peak-rps --runs 5
./infra/scripts/benchmark/run-campaign.sh --track c7i --scenario security-wall --runs 5
./infra/scripts/benchmark/run-campaign.sh --track c7i --scenario finops-stress --runs 5
./infra/scripts/benchmark/run-campaign.sh --track c7i --scenario otel-baseline --runs 1
./infra/scripts/benchmark/run-campaign.sh --track c7i --scenario marathon-24h

# Cost calculator
./infra/scripts/benchmark/cost-calculator.sh --track c7i --duration-minutes 60 --delta-accepted 1000000

# Teardown
cd infra/terraform && terraform destroy -var-file=profile_c7i.tfvars
```

See `infra/AWS_BENCHMARK_V2.md` for the full campaign protocol.

## Tech Stack

- **Java 24** (eclipse-temurin:24-jdk), **Gradle 9.3** (wrapper), **JUnit 6**
- **Netty 4.1.115** for transport (gRPC HTTP/2 + HTTP/1.1)
- Uses `jdk.incubator.vector` (SIMD masking) and `jdk.httpserver` incubator modules
- No Spring, no protobuf-java — all wire-level processing is manual

## Architecture

```
Ingress (Netty)                    Processing                     Egress
┌──────────────────┐    ┌──────────────────────────────┐    ┌───────────────────┐
│ NettyOtlpGrpcAdapter │──→│ OtlpProcessingPipeline       │──→│ AsyncOtlpHttpExporter │
│ NettyOtlpHttpAdapter │    │  1. Admission policy          │    │  (Semaphore-bounded)  │
└──────────────────┘    │  2. MutationPlanner → Plan     │    └───────────────────┘
                        │  3. MutationPlanValidator       │
                        │  4. Execute (mask/reframe/drop) │
                        │  5. Queue → Export              │
                        └──────────────────────────────────┘
```

**Key packages** (`com.acme.finops.gateway`):
- `transport/grpc`, `transport/http` — Netty ingress adapters; produce `InboundPacket`
- `transport/proxy` — Pipeline orchestration, async export, queue dispatch
- `memory` — `PacketRef` ownership/refcount, `SlabPacketAllocator` (off-heap)
- `wire/cursor` — Protobuf wire-level cursor/evaluator (no deserialization)
- `wire/mutate` — `MutationPlan`, planner, validator, reframe writer, mask writers (scalar + SIMD)
- `policy` — Path expression compiler (`OtlpPathCompiler`), policy decisions
- `backpressure` — Watermark-based throttle + queue-aware drop policy
- `queue` — `StripedMpscRing` lock-free sharded queue
- `audit` — Async WAL-based audit sink with compliance reporting
- `telemetry` — Hot-path metrics (with p99 ring buffer), Prometheus `/metrics` endpoint
- `util` — Shared constants (`GatewayEnvKeys`, `OtlpEndpoints`, `OtlpContentTypes`, `GrpcProtocolConstants`)

**Main entrypoint**: `transport/proxy/NettyGatewayProxyMain.java` (Gradle task: `runGatewayProxy`)

## Critical Invariants

1. **PacketRef ownership**: Every `retain()` must have a paired `release()`. Leaks cause direct memory exhaustion; double-release causes crashes.

2. **Zero-copy hot path**: No protobuf deserialization in the ingest path. Avoid `regex`, `streams`, `Optional` in payload processing loops. No extra `byte[]` allocations in mutation/export cycles.

3. **Mutation safety**: `MutationPlanValidator` is the mandatory gate before plan execution. For strict in-place masking, `tokenBytes` length must match value span length. Mismatch with `mode=skip` leaves value untouched (fail-open).

4. **Shared constants**: All env keys, header names, path literals, and content types must go through shared constant classes — never inline string literals for these.

5. **API contracts**: HTTP ingest success = `200`; gRPC empty payload = `grpc-status=3` (INVALID_ARGUMENT). Changing response codes requires updating smoke/soak scripts and documentation.

## Configuration

All env keys are defined in `GatewayEnvKeys.java`. Key groups:

- **Upstream:** `OTLP_UPSTREAM_{TRACES,METRICS,LOGS}_URL`
- **Queue:** `GATEWAY_QUEUE_{ENABLED,CAPACITY,SHARDS,WORKERS}`
- **Backpressure:** `GATEWAY_BACKPRESSURE_{LOW,HIGH,CRITICAL,MAX_QUEUE_WAIT_MS,SHED_*}`
- **Exporter:** `GATEWAY_MAX_INFLIGHT`, `GATEWAY_EXPORTER_POOL_SIZE`, `GATEWAY_EXPORTER_IO_THREADS`
- **Masking:** `GATEWAY_MASKING_{ENABLED,SIMD,MAX_OPS_PER_PACKET,RULES}`
- **Metrics:** `GATEWAY_METRICS_{ENABLED,HTTP_ENABLED,HTTP_PORT,HTTP_PATH,LOG_INTERVAL_SEC}`

Masking rules format:
```
ruleId|signal|action|sourcePath|redactionToken|priority|onMismatch|enabled
```

## Infra Layout

- `infra/docker-compose.yml` — gateway + upstream OTel Collector stack
- `infra/otelcol/config.yaml` — upstream collector config
- `infra/scripts/{stack,smoke,soak,preprod,benchmark}/` — grouped operational scripts
- `infra/env/` — masking rule profiles (`masking-security-wall.rules`, `masking-finops-stress.rules`)
- `infra/terraform/` — AWS 3-node benchmark provisioning (telemetrygen/gateway/upstream)
- `infra/artifacts/` — soak test and benchmark output artifacts
- `infra/AWS_BENCHMARK_V2.md` — benchmark campaign protocol (4 KPIs, 5 scenarios)
- `infra/PREPROD_CONCURRENCY_AUDIT_CHECKLIST.md` — ownership/concurrency pre-flight checklist
