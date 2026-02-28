#!/usr/bin/env bash
set -euo pipefail

# Cost calculator for AWS benchmark runs.
# Computes total infra cost and cost per 1B events based on instance pricing.

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --track <c7i|c7g>              Hardware track (required)
  --duration-minutes <n>         Benchmark duration in minutes (required)
  --delta-accepted <n>           Total accepted events during measurement (required)
  --gateway-instance <type>      Override gateway instance type
  --upstream-instance <type>     Override upstream instance type
  --telemetrygen-instance <type> Override telemetrygen instance type
  --output-file <path>           Output file (default: stdout)
  -h, --help                     Show this help

Embedded pricing (us-east-1, On-Demand, Feb 2026):
  c7i.2xlarge = \$0.357/hr     c7g.2xlarge = \$0.2894/hr
  c7i.xlarge  = \$0.1785/hr    c7g.xlarge  = \$0.1447/hr
USAGE
}

TRACK=""
DURATION_MINUTES=""
DELTA_ACCEPTED=""
GATEWAY_INSTANCE=""
UPSTREAM_INSTANCE=""
TELEMETRYGEN_INSTANCE=""
OUTPUT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --track)                TRACK="$2";                shift 2 ;;
    --duration-minutes)     DURATION_MINUTES="$2";     shift 2 ;;
    --delta-accepted)       DELTA_ACCEPTED="$2";       shift 2 ;;
    --gateway-instance)     GATEWAY_INSTANCE="$2";     shift 2 ;;
    --upstream-instance)    UPSTREAM_INSTANCE="$2";     shift 2 ;;
    --telemetrygen-instance) TELEMETRYGEN_INSTANCE="$2"; shift 2 ;;
    --output-file)          OUTPUT_FILE="$2";          shift 2 ;;
    -h|--help)              usage; exit 0 ;;
    *)                      echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "${TRACK}" || -z "${DURATION_MINUTES}" || -z "${DELTA_ACCEPTED}" ]]; then
  echo "ERROR: --track, --duration-minutes, and --delta-accepted are required" >&2
  usage
  exit 1
fi

# Instance type defaults per track
case "${TRACK}" in
  c7i)
    GATEWAY_INSTANCE="${GATEWAY_INSTANCE:-c7i.2xlarge}"
    UPSTREAM_INSTANCE="${UPSTREAM_INSTANCE:-c7i.xlarge}"
    TELEMETRYGEN_INSTANCE="${TELEMETRYGEN_INSTANCE:-c7i.2xlarge}"
    ;;
  c7g)
    GATEWAY_INSTANCE="${GATEWAY_INSTANCE:-c7g.2xlarge}"
    UPSTREAM_INSTANCE="${UPSTREAM_INSTANCE:-c7g.xlarge}"
    TELEMETRYGEN_INSTANCE="${TELEMETRYGEN_INSTANCE:-c7g.2xlarge}"
    ;;
  *)
    echo "ERROR: unsupported track '${TRACK}' (expected c7i or c7g)" >&2
    exit 1
    ;;
esac

# Pricing table (us-east-1, On-Demand, Feb 2026)
price_per_hour() {
  case "$1" in
    c7i.2xlarge) echo "0.357" ;;
    c7i.xlarge)  echo "0.1785" ;;
    c7g.2xlarge) echo "0.2894" ;;
    c7g.xlarge)  echo "0.1447" ;;
    *)
      echo "WARNING: unknown instance type '$1', using 0.00" >&2
      echo "0.00"
      ;;
  esac
}

GW_PRICE="$(price_per_hour "${GATEWAY_INSTANCE}")"
UP_PRICE="$(price_per_hour "${UPSTREAM_INSTANCE}")"
TG_PRICE="$(price_per_hour "${TELEMETRYGEN_INSTANCE}")"

result="$(awk -v gw="${GW_PRICE}" -v up="${UP_PRICE}" -v tg="${TG_PRICE}" \
             -v dur="${DURATION_MINUTES}" -v accepted="${DELTA_ACCEPTED}" '
  BEGIN {
    hours = dur / 60.0;
    gw_cost = gw * hours;
    up_cost = up * hours;
    tg_cost = tg * hours;
    total = gw_cost + up_cost + tg_cost;
    if (accepted > 0) {
      cost_per_1b = (total / accepted) * 1000000000;
    } else {
      cost_per_1b = 0;
    }
    printf "gateway_instance=%s\n", "'"${GATEWAY_INSTANCE}"'";
    printf "upstream_instance=%s\n", "'"${UPSTREAM_INSTANCE}"'";
    printf "telemetrygen_instance=%s\n", "'"${TELEMETRYGEN_INSTANCE}"'";
    printf "gateway_hourly_usd=%.4f\n", gw;
    printf "upstream_hourly_usd=%.4f\n", up;
    printf "telemetrygen_hourly_usd=%.4f\n", tg;
    printf "duration_hours=%.4f\n", hours;
    printf "gateway_cost_usd=%.4f\n", gw_cost;
    printf "upstream_cost_usd=%.4f\n", up_cost;
    printf "telemetrygen_cost_usd=%.4f\n", tg_cost;
    printf "total_infra_cost_usd=%.4f\n", total;
    printf "delta_accepted=%d\n", accepted;
    printf "cost_per_1B_events_usd=%.4f\n", cost_per_1b;
  }
')"

if [[ -n "${OUTPUT_FILE}" ]]; then
  echo "${result}" > "${OUTPUT_FILE}"
  echo "Wrote cost report: ${OUTPUT_FILE}"
else
  echo "${result}"
fi
