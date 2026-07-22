#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_DIR="${ROOT_DIR}/deploy"
ENV_FILE="${MINE_ENV_FILE:-${DEPLOY_DIR}/.env}"

for command in docker curl openssl awk df jq grep stat bash; do
  command -v "${command}" >/dev/null || { echo "FAIL: 缺少命令 ${command}" >&2; exit 1; }
done
docker info >/dev/null
docker compose version >/dev/null

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "FAIL: 请先复制 deploy/.env.example 为 deploy/.env" >&2
  exit 1
fi

env_mode="$(stat -c '%a' "${ENV_FILE}" 2>/dev/null || stat -f '%Lp' "${ENV_FILE}")"
[[ "${env_mode}" == "600" || "${env_mode}" == "400" ]] \
  || { echo "FAIL: deploy/.env 权限必须为 0600 或 0400" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

required_env=(
  POSTGRES_PASSWORD TIMESCALE_PASSWORD TIMESCALE_READER_PASSWORD TIMESCALE_REPLAY_PASSWORD REDIS_PASSWORD
  RABBITMQ_ADMIN_USERNAME RABBITMQ_ADMIN_PASSWORD RABBITMQ_GATEWAY_DEVICE_PASSWORD
  RABBITMQ_GATEWAY_PLATFORM_PASSWORD RABBITMQ_TELEMETRY_PASSWORD RABBITMQ_SERVER_REALTIME_PASSWORD
  RABBITMQ_SERVER_COMMAND_PASSWORD RABBITMQ_SERVER_ALARM_PASSWORD RABBITMQ_SERVER_OPERATION_PASSWORD
  RABBITMQ_SERVER_OPERATION_OUTBOX_PASSWORD MINE_CONFIGURATION_DISTRIBUTION_TOKEN
  MINE_SUPER_ADMIN_PASSWORD MINE_PERSON_DATA_KEY MINE_NOTIFICATION_DATA_KEY
  MINE_RUSTFS_ACCESS_KEY MINE_RUSTFS_SECRET_KEY
)
[[ "${MINE_PREFLIGHT_PROFILE:-}" != "observability" ]] || required_env+=(MINE_GRAFANA_ADMIN_PASSWORD)
for name in "${required_env[@]}"; do
  value="${!name-}"
  [[ -n "${value}" ]] || { echo "FAIL: deploy/.env 缺少 ${name}" >&2; exit 1; }
  [[ "${value}" != change-me-* ]] || { echo "FAIL: deploy/.env 中 ${name} 仍是示例占位值" >&2; exit 1; }
  if [[ "${name}:${value}" == "MINE_RUSTFS_ACCESS_KEY:mine-rustfs-dev" \
      || "${name}:${value}" == "MINE_RUSTFS_SECRET_KEY:mine-rustfs-dev-secret" ]]; then
    echo "FAIL: deploy/.env 中 ${name} 仍是旧版公开开发凭据" >&2
    exit 1
  fi
done

resolve_deploy_path() {
  local path="$1"
  if [[ "${path}" == /* ]]; then
    printf '%s\n' "${path}"
  else
    printf '%s\n' "${DEPLOY_DIR}/${path#./}"
  fi
}

server_jar="$(resolve_deploy_path "${MINE_SERVER_JAR_PATH:-./backend/mine-server.jar}")"
gateway_jar="$(resolve_deploy_path "${MINE_GATEWAY_JAR_PATH:-./backend/mine-gateway.jar}")"
telemetry_jar="$(resolve_deploy_path "${MINE_TELEMETRY_JAR_PATH:-./backend/mine-telemetry.jar}")"
web_dist="$(resolve_deploy_path "${MINE_WEB_DIST_PATH:-./frontend/dist}")"
certs_dir="$(resolve_deploy_path "${MINE_CERTS_DIR:-./certs}")"
for jar in "${server_jar}" "${gateway_jar}" "${telemetry_jar}"; do
  [[ -s "${jar}" ]] || { echo "FAIL: Java 制品缺失或为空：${jar}" >&2; exit 1; }
done
[[ -s "${web_dist}/index.html" ]] || { echo "FAIL: 前端制品缺少 index.html：${web_dist}" >&2; exit 1; }
[[ -s "${certs_dir}/tls.crt" ]] || { echo "FAIL: TLS 证书缺失：${certs_dir}/tls.crt" >&2; exit 1; }
[[ -s "${certs_dir}/tls.key" ]] || { echo "FAIL: TLS 私钥缺失：${certs_dir}/tls.key" >&2; exit 1; }
key_mode="$(stat -c '%a' "${certs_dir}/tls.key" 2>/dev/null || stat -f '%Lp' "${certs_dir}/tls.key")"
[[ "${key_mode}" == "600" || "${key_mode}" == "400" ]] \
  || { echo "FAIL: tls.key 权限必须为 0600 或 0400" >&2; exit 1; }

available_kb="$(df -Pk "${ROOT_DIR}" | awk 'NR==2 {print $4}')"
minimum_kb="$(( ${MINE_MIN_FREE_GB:-20} * 1024 * 1024 ))"
(( available_kb >= minimum_kb )) || { echo "FAIL: 可用磁盘不足 ${MINE_MIN_FREE_GB:-20} GiB" >&2; exit 1; }
(( $(ulimit -n) >= 65536 )) || { echo "FAIL: 文件句柄上限需至少 65536" >&2; exit 1; }

openssl x509 -in "${certs_dir}/tls.crt" -noout -checkend 604800 >/dev/null \
  || { echo "FAIL: TLS 证书将在 7 天内过期或格式错误" >&2; exit 1; }
compose_args=(--env-file "${ENV_FILE}" -f "${DEPLOY_DIR}/docker-compose.yml")
if [[ -n "${MINE_PREFLIGHT_PROFILE:-}" ]]; then
  compose_args+=(--profile "${MINE_PREFLIGHT_PROFILE}")
fi
docker compose "${compose_args[@]}" config --quiet
MINE_ENV_FILE="${ENV_FILE}" "${ROOT_DIR}/deploy/tests/java-artifact-isolation.sh"
echo "PASS: Docker、Compose、磁盘、文件句柄、env 凭据、证书、制品和 Compose 配置检查通过"
