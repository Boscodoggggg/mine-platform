#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${MINE_ENV_FILE:-${ROOT_DIR}/deploy/env/standalone.env}"
TIMEOUT_SECONDS="${MINE_HEALTH_TIMEOUT_SECONDS:-180}"
deadline=$((SECONDS + TIMEOUT_SECONDS))

while (( SECONDS < deadline )); do
  if MINE_ENV_FILE="${ENV_FILE}" bash "${ROOT_DIR}/deploy/tests/standalone-smoke.sh"; then
    exit 0
  fi
  sleep 5
done

docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/deploy/compose/compose-standalone.yml" ps
docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/deploy/compose/compose-standalone.yml" logs --tail 100
echo "FAIL: ${TIMEOUT_SECONDS} 秒内未达到健康状态" >&2
exit 1
