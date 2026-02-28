# AWS Benchmark Campaign Protocol V2

Proof of Efficiency at Scale — structured benchmark campaign for the OTLP Gateway.

## 4 Key Performance Indicators (KPIs)

### 1. Efficiency per Core

**Formula:**
```
events_per_core = delta_accepted / (avg_cpu_pct / 100 * num_cores)
efficiency_ratio = gateway_events_per_core / otel_events_per_core
```

**Target:** efficiency_ratio >= 3.0 (gateway is 3-5x more efficient than vanilla OTel Collector).

### 2. Deterministic Latency

**Metrics:**
- `avg_e2e_ms` — average end-to-end latency (gateway_end_to_end_duration_nanos_sum / count)
- `p99_e2e_ms` — 99th percentile end-to-end latency (gateway_end_to_end_p99_nanos)

**Formula:**
```
latency_ratio = p99_e2e_ms / avg_e2e_ms
```

**PASS gates:**
- avg_e2e_ms < 2.0ms
- p99_e2e_ms < 1.0ms
- latency_ratio < 2.0

### 3. Cost per 1 Billion Events

**Formula:**
```
total_infra_cost_usd = (gateway_hr + upstream_hr + telemetrygen_hr) * (duration_min / 60)
cost_per_1B_events = (total_infra_cost_usd / delta_accepted) * 1,000,000,000
```

**Pricing (us-east-1, On-Demand):**
| Instance | $/hr |
|----------|------|
| c7i.2xlarge | 0.3570 |
| c7i.xlarge | 0.1785 |
| c7g.2xlarge | 0.2894 |
| c7g.xlarge | 0.1447 |

### 4. Zero-Error Resilience

**Metrics (24h marathon):**
- Total events processed (delta_accepted)
- Error rate: 0 parse errors, 0 OOM markers, 0 leak markers, 0 double-release markers
- Memory growth: < 5% over 24 hours

**PASS gate:** All error counters = 0 AND memory_growth_pct < 5.0.

---

## 5 Scenarios

### 1. Peak RPS
- **Purpose:** Maximum throughput under standard masking load
- **Masking:** 4 rules (3 REDACT_MASK + 1 DROP LOGS)
- **Duration:** 60 minutes × 5 runs
- **Rate:** 15,000 events/sec/signal × 16 workers

### 2. Security Wall
- **Purpose:** Prove "security is free" — heavy masking has negligible latency impact
- **Masking:** 12 wildcard-heavy rules (6 traces, 2 metrics, 4 logs)
- **File:** `infra/env/masking-security-wall.rules`
- **Duration:** 60 minutes × 5 runs
- **Comparison:** p99 delta vs Peak RPS should be < 10%

### 3. FinOps Stress
- **Purpose:** Measure egress savings from selective traffic drop
- **Masking:** 4 rules with DROP ALL LOGS (~33% egress savings)
- **File:** `infra/env/masking-finops-stress.rules`
- **Duration:** 60 minutes × 5 runs
- **Metric:** `egress_savings_pct` in finops-summary.txt

### 4. OTel Collector Baseline
- **Purpose:** Control group for efficiency comparison
- **Config:** Vanilla OTel Collector with batch processor (32768/200ms)
- **Script:** `infra/scripts/benchmark/otel-baseline.sh`
- **Duration:** 60 minutes × 1 run per track
- **Output:** `otel_events_per_core` for efficiency_ratio calculation

### 5. Marathon 24h
- **Purpose:** Prove zero-error resilience at sustained load
- **Duration:** 24 hours × 1 run (c7i track only)
- **Rate:** 100,000 events/sec/signal × 16 workers
- **SSM timeout:** 90,000 seconds (25 hours)
- **Metrics interval:** 60 seconds

---

## PASS Gates

| Gate | Metric | Threshold | Applies To |
|------|--------|-----------|------------|
| Latency avg | avg_e2e_ms | < 2.0ms | All scenarios |
| Latency p99 | p99_e2e_ms | < 1.0ms | All scenarios |
| Latency ratio | p99/avg | < 2.0 | All scenarios |
| SIMD active | simd_active_metric | = 1 | All scenarios |
| SIMD strict | simd_strict_metric | = 1 | All scenarios |
| Memory growth | memory_growth_pct | < 5.0% | marathon-24h |
| Leak markers | log_leak_markers | = 0 | marathon-24h |
| OOM markers | log_out_of_direct_memory_markers | = 0 | All scenarios |
| Double-release | log_double_release_markers | = 0 | All scenarios |
| Efficiency | efficiency_ratio | >= 3.0 | otel-baseline |
| Egress savings | egress_savings_pct | >= 25% | finops-stress |
| Security delta | p99 delta vs peak-rps | < 10% | security-wall |

---

## Benchmark Matrix

```
                      Track A (c7i Intel)     Track B (c7g Graviton)
                      ───────────────────     ──────────────────────
Peak RPS              60min × 5 runs         60min × 5 runs
Security Wall         60min × 5 runs         60min × 5 runs
FinOps Stress         60min × 5 runs         60min × 5 runs
OTel Baseline         60min × 1 run          60min × 1 run
Marathon 24h          24h × 1 run            (skip)
                      ───────────────────     ──────────────────────
Total runs:           16                      11
Est. infra hours:     ~41h                    ~30h
Est. cost (c7i):      ~$37                    ~$25
```

---

## Running the Campaign

### Prerequisites
1. Terraform applied: `cd infra/terraform && terraform apply -var-file=profile_c7i.tfvars`
2. All 3 nodes SSM-online
3. Gateway image built or available

### Execution

```bash
# Single scenario
./infra/scripts/benchmark/run-campaign.sh \
  --track c7i --scenario peak-rps --runs 5

# Full campaign (run each scenario)
for scenario in peak-rps security-wall finops-stress otel-baseline marathon-24h; do
  ./infra/scripts/benchmark/run-campaign.sh \
    --track c7i --scenario "${scenario}"
done

# Graviton track
for scenario in peak-rps security-wall finops-stress otel-baseline; do
  ./infra/scripts/benchmark/run-campaign.sh \
    --track c7g --scenario "${scenario}"
done
```

### Dry-run validation
```bash
./infra/scripts/benchmark/run-campaign.sh \
  --track c7i --scenario peak-rps --runs 1 --dry-run
```

### Cost calculator standalone
```bash
./infra/scripts/benchmark/cost-calculator.sh \
  --track c7i --duration-minutes 60 --delta-accepted 1000000
```

---

## Artifact Structure

```
infra/artifacts/benchmark-v2/
└── YYYY-MM-DD/
    ├── c7i/
    │   ├── peak-rps/run-{001..005}/
    │   │   ├── run-meta.json
    │   │   ├── finops-summary.txt
    │   │   ├── cost-report.txt
    │   │   ├── metrics-before.prom
    │   │   ├── metrics-after.prom
    │   │   ├── metrics.prom
    │   │   ├── gateway.log
    │   │   ├── upstream.log
    │   │   └── telemetrygen-{traces,metrics,logs}.log
    │   ├── security-wall/run-{001..005}/
    │   ├── finops-stress/run-{001..005}/
    │   ├── otel-baseline/run-001/
    │   │   ├── run-meta.json
    │   │   ├── otel-baseline-summary.txt
    │   │   ├── otel-baseline-config.yaml
    │   │   ├── otel-baseline-cpu.log
    │   │   └── otel-baseline.log
    │   └── marathon-24h/run-001/
    ├── c7g/
    │   ├── peak-rps/run-{001..005}/
    │   ├── security-wall/run-{001..005}/
    │   ├── finops-stress/run-{001..005}/
    │   └── otel-baseline/run-001/
    ├── campaign-report.md
    └── campaign-report.json
```

---

## Artifact Contract

Every run directory MUST contain:
1. `run-meta.json` — full environment metadata (generated by `generate-run-meta.sh`)
2. `finops-summary.txt` (or `otel-baseline-summary.txt`) — KPI metrics
3. `cost-report.txt` — infrastructure cost breakdown

Campaign directory MUST contain:
1. `campaign-report.md` — human-readable summary tables
2. `campaign-report.json` — machine-readable run data

---

## Publication Policy

A campaign is publishable when:
1. All PASS gates met for all runs in the scenario
2. At least 5 runs completed per non-baseline scenario
3. OTel baseline collected for the track
4. `campaign-report.md` and `campaign-report.json` generated
5. All `run-meta.json` files present

---

## Scripts Reference

| Script | Purpose |
|--------|---------|
| `infra/scripts/benchmark/run-campaign.sh` | Campaign orchestrator |
| `infra/scripts/benchmark/otel-baseline.sh` | OTel Collector baseline runner |
| `infra/scripts/benchmark/generate-run-meta.sh` | Run metadata generator |
| `infra/scripts/benchmark/cost-calculator.sh` | Cost per 1B events calculator |
| `infra/scripts/benchmark/report-generator.sh` | Campaign report generator |
| `infra/terraform/scripts/deploy_via_ssm.sh` | AWS SSM deployment orchestrator |
| `infra/scripts/soak/finops-realistic.sh` | FinOps soak test runner |
| `infra/scripts/preprod/hardening-run.sh` | Pre-prod 3-stage hardening |
| `infra/scripts/preprod/bottleneck-triage.sh` | Automated bottleneck diagnosis |
