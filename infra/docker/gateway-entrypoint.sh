#!/usr/bin/env bash
set -euo pipefail

GRPC_PORT="${GATEWAY_GRPC_PORT:-4317}"
HTTP_PORT="${GATEWAY_HTTP_PORT:-4318}"

echo "[gateway-entrypoint] starting proxy on grpc=${GRPC_PORT} http=${HTTP_PORT}"
exec /app/gradlew --no-daemon -p /app runGatewayProxy --args="${GRPC_PORT} ${HTTP_PORT}"
