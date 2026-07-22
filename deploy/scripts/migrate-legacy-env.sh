#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${MINE_ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
DEPLOY_DIR="${ROOT_DIR}/deploy"
LEGACY_ENV_FILE="${MINE_LEGACY_ENV_FILE:-${DEPLOY_DIR}/env/standalone.env}"
LEGACY_SECRETS_DIR="${MINE_LEGACY_SECRETS_DIR:-${DEPLOY_DIR}/secrets}"
TARGET_ENV_FILE="${MINE_TARGET_ENV_FILE:-${DEPLOY_DIR}/.env}"
FORCE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force) FORCE=true; shift ;;
    *) echo "FAIL: 未知参数 $1" >&2; exit 2 ;;
  esac
done

[[ -f "${DEPLOY_DIR}/.env.example" ]] || { echo "FAIL: 缺少 deploy/.env.example" >&2; exit 1; }
[[ -f "${LEGACY_ENV_FILE}" ]] || { echo "FAIL: 缺少旧环境文件 ${LEGACY_ENV_FILE}" >&2; exit 1; }
[[ -d "${LEGACY_SECRETS_DIR}" ]] || { echo "FAIL: 缺少旧凭据目录 ${LEGACY_SECRETS_DIR}" >&2; exit 1; }
command -v openssl >/dev/null || { echo "FAIL: 缺少命令 openssl" >&2; exit 1; }
if [[ -e "${TARGET_ENV_FILE}" && "${FORCE}" != "true" ]]; then
  echo "FAIL: ${TARGET_ENV_FILE} 已存在；确认覆盖后使用 --force" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${LEGACY_ENV_FILE}"
set +a

load_legacy_value() {
  local file_name="$1"
  local variable_name="$2"
  local path="${LEGACY_SECRETS_DIR}/${file_name}"
  [[ -s "${path}" ]] || { echo "FAIL: 旧凭据缺失或为空：${path}" >&2; exit 1; }
  local value
  value="$(<"${path}")"
  [[ -n "${value}" && "${value}" != *$'\n'* && "${value}" != *$'\r'* ]] \
    || { echo "FAIL: 旧凭据必须是单行非空值：${path}" >&2; exit 1; }
  printf -v "${variable_name}" '%s' "${value}"
  export "${variable_name}"
}

load_legacy_value postgres_password POSTGRES_PASSWORD
load_legacy_value timescale_password TIMESCALE_PASSWORD
load_legacy_value timescale_reader_password TIMESCALE_READER_PASSWORD
load_legacy_value timescale_replay_password TIMESCALE_REPLAY_PASSWORD
load_legacy_value redis_password REDIS_PASSWORD
load_legacy_value rabbitmq_admin_username RABBITMQ_ADMIN_USERNAME
load_legacy_value rabbitmq_admin_password RABBITMQ_ADMIN_PASSWORD
load_legacy_value rabbitmq_gateway_device_password RABBITMQ_GATEWAY_DEVICE_PASSWORD
load_legacy_value rabbitmq_gateway_platform_password RABBITMQ_GATEWAY_PLATFORM_PASSWORD
load_legacy_value rabbitmq_telemetry_password RABBITMQ_TELEMETRY_PASSWORD
load_legacy_value rabbitmq_server_realtime_password RABBITMQ_SERVER_REALTIME_PASSWORD
load_legacy_value rabbitmq_server_command_password RABBITMQ_SERVER_COMMAND_PASSWORD
load_legacy_value rabbitmq_server_alarm_password RABBITMQ_SERVER_ALARM_PASSWORD
load_legacy_value rabbitmq_server_operation_password RABBITMQ_SERVER_OPERATION_PASSWORD
load_legacy_value rabbitmq_server_operation_outbox_password RABBITMQ_SERVER_OPERATION_OUTBOX_PASSWORD
load_legacy_value mine_configuration_distribution_token MINE_CONFIGURATION_DISTRIBUTION_TOKEN
load_legacy_value mine_super_admin_password MINE_SUPER_ADMIN_PASSWORD
load_legacy_value mine_person_data_key MINE_PERSON_DATA_KEY
load_legacy_value mine_notification_data_key MINE_NOTIFICATION_DATA_KEY
load_legacy_value grafana_admin_password MINE_GRAFANA_ADMIN_PASSWORD

if [[ -z "${MINE_RUSTFS_ACCESS_KEY:-}" || "${MINE_RUSTFS_ACCESS_KEY}" == "mine-rustfs-dev" \
    || "${MINE_RUSTFS_ACCESS_KEY}" == change-me-* ]]; then
  MINE_RUSTFS_ACCESS_KEY="$(openssl rand -hex 16)"
fi
if [[ -z "${MINE_RUSTFS_SECRET_KEY:-}" || "${MINE_RUSTFS_SECRET_KEY}" == "mine-rustfs-dev-secret" \
    || "${MINE_RUSTFS_SECRET_KEY}" == change-me-* ]]; then
  MINE_RUSTFS_SECRET_KEY="$(openssl rand -hex 32)"
fi
export MINE_RUSTFS_ACCESS_KEY MINE_RUSTFS_SECRET_KEY

MINE_SERVER_JAR_PATH=./backend/mine-server.jar
MINE_GATEWAY_JAR_PATH=./backend/mine-gateway.jar
MINE_TELEMETRY_JAR_PATH=./backend/mine-telemetry.jar
MINE_WEB_DIST_PATH=./frontend/dist
MINE_CERTS_DIR=./certs
export MINE_SERVER_JAR_PATH MINE_GATEWAY_JAR_PATH MINE_TELEMETRY_JAR_PATH MINE_WEB_DIST_PATH MINE_CERTS_DIR

mkdir -p "$(dirname "${TARGET_ENV_FILE}")" "${DEPLOY_DIR}/certs"
temporary_env="$(mktemp "${TARGET_ENV_FILE}.tmp.XXXXXX")"
trap 'rm -f "${temporary_env}"' EXIT
while IFS= read -r line || [[ -n "${line}" ]]; do
  if [[ "${line}" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]]; then
    key="${BASH_REMATCH[1]}"
    default_value="${BASH_REMATCH[2]}"
    if [[ -n "${!key+x}" ]]; then
      value="${!key}"
    else
      value="${default_value}"
    fi
    escaped="${value//\'/\'\\\'\'}"
    printf "%s='%s'\n" "${key}" "${escaped}" >>"${temporary_env}"
  else
    printf '%s\n' "${line}" >>"${temporary_env}"
  fi
done <"${DEPLOY_DIR}/.env.example"
chmod 0600 "${temporary_env}"
mv -f "${temporary_env}" "${TARGET_ENV_FILE}"
trap - EXIT

install -m 0644 "${LEGACY_SECRETS_DIR}/tls.crt" "${DEPLOY_DIR}/certs/tls.crt"
install -m 0600 "${LEGACY_SECRETS_DIR}/tls.key" "${DEPLOY_DIR}/certs/tls.key"

echo "PASS: 已生成 deploy/.env 并迁移 TLS 文件；验证新部署前不会删除旧 env/secret"
