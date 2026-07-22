#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${MINE_ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
ENV_FILE="${MINE_ENV_FILE:-${ROOT_DIR}/deploy/.env}"
[[ -f "${ENV_FILE}" ]] || { echo "FAIL: 缺少 ${ENV_FILE}" >&2; exit 1; }
profile_override_set="${MINE_DEPLOY_PROFILE+x}"
profile_override="${MINE_DEPLOY_PROFILE-}"
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a
if [[ -n "${profile_override_set}" ]]; then
  PROFILE="${profile_override}"
else
  PROFILE="${MINE_DEPLOY_PROFILE:-}"
fi

MINE_ENV_FILE="${ENV_FILE}" MINE_PREFLIGHT_PROFILE="${PROFILE}" "${ROOT_DIR}/deploy/scripts/preflight.sh"
compose=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/deploy/docker-compose.yml")
[[ -z "${PROFILE}" ]] || compose+=(--profile "${PROFILE}")
"${compose[@]}" up -d "$@"
MINE_ENV_FILE="${ENV_FILE}" "${ROOT_DIR}/deploy/scripts/health-check.sh"
