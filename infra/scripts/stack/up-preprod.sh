#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_FILE="${PREPROD_ENV_FILE:-${ROOT_DIR}/infra/.env.preprod}"

if [[ ! -f "${ENV_FILE}" ]]; then
  if [[ -f "${ROOT_DIR}/infra/.env.preprod.example" ]]; then
    ENV_FILE="${ROOT_DIR}/infra/.env.preprod.example"
  elif [[ -f "${ROOT_DIR}/infra/.env.example" ]]; then
    ENV_FILE="${ROOT_DIR}/infra/.env.example"
  else
    echo "FAILED: no pre-prod env profile found (expected infra/.env.preprod or infra/.env.preprod.example)" >&2
    exit 1
  fi
fi

echo "Using pre-prod env profile: ${ENV_FILE}"
docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/infra/docker-compose.yml" up -d --build
echo "Pre-prod stack is up. Check logs: ${ROOT_DIR}/infra/scripts/stack/logs.sh"
