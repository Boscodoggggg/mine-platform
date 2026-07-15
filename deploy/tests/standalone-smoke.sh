#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${MINE_COMPOSE_FILE:-${ROOT_DIR}/deploy/compose/compose-standalone.yml}"
ENV_FILE="${MINE_ENV_FILE:-${ROOT_DIR}/deploy/env/standalone.env}"

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "FAIL: 缺少单机 Compose：${COMPOSE_FILE}" >&2
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "FAIL: 缺少环境变量文件：${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

compose=(docker compose)
if [[ -n "${MINE_COMPOSE_PROJECT_NAME:-}" ]]; then
  compose+=(-p "${MINE_COMPOSE_PROJECT_NAME}")
fi
compose+=(--env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
"${compose[@]}" config --quiet

services=(nginx mine-server mine-gateway mine-telemetry rabbitmq postgres timescale redis)
for service in "${services[@]}"; do
  container_id="$("${compose[@]}" ps --quiet "${service}")"
  if [[ -z "${container_id}" ]]; then
    echo "FAIL: ${service} 未运行" >&2
    exit 1
  fi
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
  if [[ "${health}" != "healthy" && "${health}" != "running" ]]; then
    echo "FAIL: ${service} 状态为 ${health}" >&2
    exit 1
  fi
done

curl --fail --silent --show-error --insecure "https://127.0.0.1:${MINE_HTTPS_PORT:-443}/api/v1/system/status" \
  | grep -q '"status":"UP"'
curl --fail --silent --show-error --insecure "https://127.0.0.1:${MINE_HTTPS_PORT:-443}/" \
  | grep -qi '<!doctype html>'
openssl s_client -connect "127.0.0.1:${MINE_MQTTS_PORT:-8883}" -servername "${MINE_TLS_SERVER_NAME:-mine.local}" \
  </dev/null 2>/dev/null | grep -q "BEGIN CERTIFICATE"

"${compose[@]}" exec -T postgres pg_isready -U "${POSTGRES_USER:-mine}" -d "${POSTGRES_DB:-mine}"
"${compose[@]}" exec -T timescale psql -U "${TIMESCALE_USER:-mine_ts}" -d "${TIMESCALE_DB:-mine_telemetry}" \
  -tAc "SELECT extname FROM pg_extension WHERE extname = 'timescaledb'" | grep -q timescaledb
"${compose[@]}" exec -T timescale psql -U "${TIMESCALE_USER:-mine_ts}" -d "${TIMESCALE_DB:-mine_telemetry}" \
  -tAc "SHOW timescaledb.license" | grep -qx apache
"${compose[@]}" exec -T rabbitmq rabbitmq-diagnostics -q ping
"${compose[@]}" exec -T rabbitmq sh -ec \
  'rabbitmqctl -q authenticate_user "$(cat /run/secrets/rabbitmq_admin_username)" "$(cat /run/secrets/rabbitmq_admin_password)"'
"${compose[@]}" exec -T rabbitmq sh -ec \
  'rabbitmqctl -q list_user_permissions "$(cat /run/secrets/rabbitmq_admin_username)"' \
  | grep -q '^/mine-platform'
"${compose[@]}" exec -T rabbitmq sh -ec \
  'rabbitmqctl -q authenticate_user mine-telemetry "$(cat /run/secrets/rabbitmq_telemetry_password)"'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_user_permissions mine-gateway-device \
  | grep -Eq '^/mine-device[[:space:]]+\^\$[[:space:]]+\^amq\\\.topic\$[[:space:]]+\^\(mine\\\.gateway\\\.uplink\\\.q\|mine\\\.gateway\\\.command-ack\\\.q\)\$$'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_user_permissions mine-gateway-platform \
  | grep -Eq '^/mine-platform[[:space:]]+\^\$[[:space:]]+\^\(mine\\\.telemetry\\\.x\|mine\\\.command\\\.x\)\$[[:space:]]+\^mine\\\.gateway\\\.command\\\.q\$$'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_user_permissions mine-telemetry \
  | grep -Eq '^/mine-platform[[:space:]]+\^\$[[:space:]]+\^mine\\\.realtime\\\.x\$[[:space:]]+\^mine\\\.telemetry\\\.q\$$'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_user_permissions mine-server-realtime \
  | awk -F '\t' '$1 == "/mine-platform" && $2 == $3 && $3 == $4 && index($2, "mine\\.realtime\\.server") { found = 1 } END { exit !found }'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p /mine-platform name consumers \
  | awk '$1 == "mine.realtime.server.standalone.q" && $2 >= 1 { found = 1 } END { exit !found }'
"${compose[@]}" exec -T rabbitmq sh -ec \
  'rabbitmqctl -q authenticate_user mine-server-command "$(cat /run/secrets/rabbitmq_server_command_password)"'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_user_permissions mine-server-command \
  | grep -Eq '^/mine-platform[[:space:]]+\^\$[[:space:]]+\^mine\\\.command\\\.x\$[[:space:]]+\^mine\\\.server\\\.command-status\\\.q\$$'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p /mine-device name consumers \
  | awk '$1 == "mine.gateway.command-ack.q" && $2 >= 1 { found = 1 } END { exit !found }'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p /mine-platform name consumers \
  | awk '$1 == "mine.gateway.command.q" && $2 >= 1 { found = 1 } END { exit !found }'
"${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p /mine-platform name consumers \
  | awk '$1 == "mine.server.command-status.q" && $2 >= 1 { found = 1 } END { exit !found }'
if "${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p /mine-platform name \
  | grep -qx 'mine.command.q'; then
  echo "FAIL: 旧命令通配队列 mine.command.q 未清理" >&2
  exit 1
fi
"${compose[@]}" exec -T redis sh -ec \
  'redis-cli --no-auth-warning -a "$(cat /run/secrets/redis_password)" ping' | grep -q PONG

if [[ -n "${MINE_SMOKE_ADMIN_PASSWORD:-}" ]]; then
  api_base="https://127.0.0.1:${MINE_HTTPS_PORT:-443}/api/v1"
  login_payload="$(mktemp)"
  login_response="$(mktemp)"
  trap 'rm -f "${login_payload}" "${login_response}"' EXIT
  chmod 600 "${login_payload}" "${login_response}"

  jq -n \
    --arg username "${MINE_BOOTSTRAP_ADMIN_USERNAME:-mine-admin}" \
    --arg password "${MINE_SMOKE_ADMIN_PASSWORD}" \
    '{username: $username, password: $password}' >"${login_payload}"
  curl --fail --silent --show-error --insecure \
    -H 'Content-Type: application/json' \
    --data-binary "@${login_payload}" \
    "${api_base}/auth/login" >"${login_response}"

  token_name="$(jq -r '.tokenName' "${login_response}")"
  token_value="$(jq -r '.tokenValue' "${login_response}")"
  if [[ ! "${token_name}" =~ ^[A-Za-z0-9-]{1,64}$ || -z "${token_value}" || "${token_value}" == "null" ]]; then
    echo "FAIL: 管理员登录未返回有效令牌" >&2
    exit 1
  fi
  if [[ "$(jq -r '.user.passwordChangeRequired' "${login_response}")" == "true" ]]; then
    echo "FAIL: 冒烟管理员仍要求首次改密，请先完成改密后再设置 MINE_SMOKE_ADMIN_PASSWORD" >&2
    exit 1
  fi

  auth_header="${token_name}: ${token_value}"
  curl --fail --silent --show-error --insecure -H "${auth_header}" "${api_base}/auth/me" \
    | jq -e '.id and .username' >/dev/null
  curl --fail --silent --show-error --insecure -H "${auth_header}" "${api_base}/auth/menus" \
    | jq -e '.permissions | type == "array"' >/dev/null
  curl --fail --silent --show-error --insecure -H "${auth_header}" "${api_base}/system/users?page=1&size=20" \
    | jq -e '.items | type == "array"' >/dev/null
  curl --fail --silent --show-error --insecure -H "${auth_header}" "${api_base}/system/roles?page=1&size=20" \
    | jq -e '.items | type == "array"' >/dev/null
  curl --fail --silent --show-error --insecure -H "${auth_header}" "${api_base}/system/menus/tree" \
    | jq -e 'type == "array"' >/dev/null
  echo "PASS: 管理员登录、动态权限、用户、角色和只读菜单树接口均正常"
else
  echo "SKIP: 未设置 MINE_SMOKE_ADMIN_PASSWORD，跳过需要管理员凭据的访问管理冒烟"
fi

echo "PASS: 单机 Compose 核心服务、HTTPS、MQTTS、数据库、TimescaleDB、RabbitMQ 与 Redis 均正常"
