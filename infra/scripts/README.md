# Scripts Layout

Scripts are grouped by purpose. High-RPS is the default posture (`workers=16`, `rate=15000`, `metrics_interval_sec=10`).

## `stack/` — Docker Compose lifecycle

| Script | Purpose |
|--------|---------|
| `up.sh` | Build + start gateway + upstream |
| `up-preprod.sh` | Start with pre-prod env profile |
| `down.sh` | Stop stack and remove orphans |
| `logs.sh` | Tail gateway + upstream logs |
| `build.sh` | Build gateway image only |

## `smoke/` — Functional verification

| Script | Purpose |
|--------|---------|
| `valid-otlp.sh` | Send valid OTLP JSON (traces/metrics/logs), verify HTTP 200 (**preferred**) |
| `masking-explicit.sh` | Verify PII masking: send tenant_id, assert masked token in upstream |
| `grpc.sh` | gRPC smoke via Gradle client task |
| `http.sh` | Minimal transport-level probe (synthetic payload) |

## `soak/` — Load and endurance testing

| Script | Purpose |
|--------|---------|
| `finops-realistic.sh` | FinOps policy profile soak: SIMD masking + DROP logs + summary with p99/egress savings |
| `telemetrygen.sh` | Long-duration soak with telemetrygen containers + artifact capture |
| `valid-otlp.sh` | Parallel valid OTLP JSON load (30k requests, concurrency 128) |
| `http.sh` | Raw HTTP concurrency soak (10k requests, concurrency 64) |

## `preprod/` — Pre-production gates

| Script | Purpose |
|--------|---------|
| `hardening-run.sh` | 3-stage protocol (warmup 15m / measure 60m / cooldown 5m) + go/no-go gate |
| `bottleneck-triage.sh` | P1/P2/P3 automated diagnosis from soak artifacts |

## `benchmark/` — AWS benchmark campaign (V2)

| Script | Purpose |
|--------|---------|
| `run-campaign.sh` | Campaign orchestrator: runs N iterations of a scenario on a track |
| `otel-baseline.sh` | OTel Collector control group for efficiency comparison |
| `generate-run-meta.sh` | Generates `run-meta.json` with full environment metadata |
| `cost-calculator.sh` | Computes infrastructure cost and cost per 1B events |
| `report-generator.sh` | Assembles `campaign-report.md` + `campaign-report.json` from artifacts |

See `infra/AWS_BENCHMARK_V2.md` for the full campaign protocol.

## Quick examples

```bash
# Local development
./infra/scripts/stack/up.sh
./infra/scripts/smoke/valid-otlp.sh
./infra/scripts/soak/finops-realistic.sh 10m 16 15000 10
./infra/scripts/preprod/hardening-run.sh
./infra/scripts/stack/down.sh

# AWS benchmark (after terraform apply)
./infra/scripts/benchmark/run-campaign.sh --track c7i --scenario peak-rps --runs 5
./infra/scripts/benchmark/cost-calculator.sh --track c7i --duration-minutes 60 --delta-accepted 1000000
```
