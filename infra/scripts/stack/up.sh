#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

if [[ -f "${ROOT_DIR}/infra/.env" ]]; then
  docker compose --env-file "${ROOT_DIR}/infra/.env" -f "${ROOT_DIR}/infra/docker-compose.yml" up -d --build
else
  docker compose -f "${ROOT_DIR}/infra/docker-compose.yml" up -d --build
fi

echo "Stack is up. Check logs: ${ROOT_DIR}/infra/scripts/stack/logs.sh"
