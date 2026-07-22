#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${MINE_ENV_FILE:-${ROOT_DIR}/deploy/.env}"
[[ -f "${ENV_FILE}" ]] || { echo "FAIL: 缺少 ${ENV_FILE}" >&2; exit 1; }
docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/deploy/docker-compose.yml" down "$@"
