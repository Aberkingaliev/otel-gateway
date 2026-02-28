#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

if [[ -f "${ROOT_DIR}/infra/.env" ]]; then
  docker compose --env-file "${ROOT_DIR}/infra/.env" -f "${ROOT_DIR}/infra/docker-compose.yml" logs -f --tail=200 gateway otel-upstream
else
  docker compose -f "${ROOT_DIR}/infra/docker-compose.yml" logs -f --tail=200 gateway otel-upstream
fi
