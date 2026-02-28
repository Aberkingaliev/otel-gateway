# Terraform: AWS Benchmark Infra

Provisions a reproducible 3-node benchmark layout for the FinOps gateway campaign:

- `telemetrygen` node (load generator)
- `gateway` node
- `upstream` node (OTel Collector)

Infrastructure: VPC + public subnet + IGW + SG + IAM/SSM + CloudWatch + 3 EC2 instances.

## Files

| File | Purpose |
|------|---------|
| `providers.tf` | AWS provider + pinned versions |
| `variables.tf` | Input variables |
| `main.tf` | VPC/SG/IAM/EC2 resources |
| `outputs.tf` | Instance IDs, private IPs, metadata |
| `terraform.tfvars.example` | Variable template |
| `profile_c7i.tfvars` | x86 (c7i) track |
| `profile_c7g.tfvars` | Graviton (c7g) track |

## Usage

```bash
cd infra/terraform
terraform init

cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars (availability_zone, operator_ingress_cidrs, key_name)

terraform plan  -var-file=profile_c7i.tfvars
terraform apply -var-file=profile_c7i.tfvars
```

Destroy:

```bash
terraform destroy -var-file=profile_c7i.tfvars
```

## Single-run deploy + soak (SSM)

After `terraform apply`, run the orchestrator:

```bash
./infra/terraform/scripts/deploy_via_ssm.sh \
  --tf-dir ./infra/terraform \
  --aws-profile <your-profile> \
  --duration 60m \
  --workers 16 \
  --rate 15000 \
  --metrics-interval-sec 10 \
  --artifact-s3-uri s3://<bucket>/<prefix>
```

### All deploy_via_ssm.sh flags

| Flag | Default | Description |
|------|---------|-------------|
| `--tf-dir <path>` | `infra/terraform` | Terraform directory |
| `--aws-profile <name>` | — | AWS CLI profile |
| `--region <aws-region>` | from terraform output | Override region |
| `--run-id <id>` | `bench-YYYYmmdd_HHMMSS` | Benchmark run ID |
| `--deploy-only` | false | Deploy only, skip soak |
| `--gateway-image <image>` | — | Prebuilt image (if empty, build from repo) |
| `--repo-url <url>` | git origin | Repository URL for remote build |
| `--repo-ref <ref>` | HEAD | Git ref for remote build |
| `--duration <dur>` | `60m` | Soak duration |
| `--workers <n>` | `16` | Telemetrygen workers |
| `--rate <rps>` | `15000` | Rate per signal |
| `--metrics-interval-sec <n>` | `10` | Metrics snapshot interval |
| `--tenant-id <id>` | `black_list` | Workload tenant ID |
| `--artifact-s3-uri <s3://...>` | — | S3 upload prefix |
| `--telemetrygen-image <image>` | `ghcr.io/.../telemetrygen:latest` | Telemetrygen image |
| `--max-inflight <n>` | `8192` | Max inflight packets |
| `--exporter-pool-size <n>` | `64` | HTTP connection pool size |
| `--exporter-io-threads <n>` | `0` (auto) | HTTP exporter IO threads |
| `--ssm-timeout <seconds>` | `7200` | SSM execution timeout (use 90000 for marathon) |

## Campaign orchestrator

For multi-run campaigns across scenarios, use `run-campaign.sh`:

```bash
./infra/scripts/benchmark/run-campaign.sh \
  --track c7i \
  --scenario peak-rps \
  --runs 5

# Scenarios: peak-rps, security-wall, finops-stress, otel-baseline, marathon-24h
```

See `infra/AWS_BENCHMARK_V2.md` for the full campaign protocol.

## Notes

- Region defaults to `us-east-1`.
- AMI: Amazon Linux 2023, selected by `instance_architecture` (`x86_64` or `arm64`).
- User-data installs Docker and CloudWatch Agent.
- `.terraform.lock.hcl` is committed for reproducible provider resolution.
