#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/deploy/openviking"

cd "${COMPOSE_DIR}"
docker compose up -d --wait
curl -fsS http://127.0.0.1:1933/health
echo
