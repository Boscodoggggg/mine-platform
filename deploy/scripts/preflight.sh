#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${MINE_ENV_FILE:-${ROOT_DIR}/deploy/env/standalone.env}"

for command in docker curl openssl awk df jq grep stat bash; do
  command -v "${command}" >/dev/null || { echo "FAIL: 缺少命令 ${command}" >&2; exit 1; }
done
docker info >/dev/null
docker compose version >/dev/null

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "FAIL: 请先复制 deploy/env/standalone.env.example 为 standalone.env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a
secrets_dir="$(cd "${ROOT_DIR}/deploy/compose" && cd "${MINE_SECRETS_DIR:-../secrets}" 2>/dev/null && pwd || true)"
if [[ -z "${secrets_dir}" ]]; then
  echo "FAIL: secret 目录不存在：${MINE_SECRETS_DIR:-../secrets}" >&2
  exit 1
fi

required=(postgres_password timescale_password timescale_reader_password redis_password rabbitmq_admin_username rabbitmq_admin_password rabbitmq_gateway_device_password rabbitmq_gateway_platform_password rabbitmq_telemetry_password rabbitmq_server_realtime_password rabbitmq_server_command_password mine_super_admin_password tls.crt tls.key)
if [[ "${MINE_PREFLIGHT_PROFILE:-}" == "observability" ]]; then
  required+=(grafana_admin_password)
fi
for name in "${required[@]}"; do
  file="${secrets_dir}/${name}"
  [[ -s "${file}" ]] || { echo "FAIL: secret 缺失或为空：${file}" >&2; exit 1; }
  if [[ "${name}" == "tls.key" ]]; then
    mode="$(stat -f '%Lp' "${file}" 2>/dev/null || stat -c '%a' "${file}")"
    [[ "${mode}" == "600" || "${mode}" == "400" ]] || { echo "FAIL: tls.key 权限必须为 0600 或 0400" >&2; exit 1; }
  fi
done

available_kb="$(df -Pk "${ROOT_DIR}" | awk 'NR==2 {print $4}')"
minimum_kb="$(( ${MINE_MIN_FREE_GB:-20} * 1024 * 1024 ))"
(( available_kb >= minimum_kb )) || { echo "FAIL: 可用磁盘不足 ${MINE_MIN_FREE_GB:-20} GiB" >&2; exit 1; }
(( $(ulimit -n) >= 65536 )) || { echo "FAIL: 文件句柄上限需至少 65536" >&2; exit 1; }

openssl x509 -in "${secrets_dir}/tls.crt" -noout -checkend 604800 >/dev/null \
  || { echo "FAIL: TLS 证书将在 7 天内过期或格式错误" >&2; exit 1; }
compose_args=(--env-file "${ENV_FILE}" -f "${ROOT_DIR}/deploy/compose/compose-standalone.yml")
if [[ -n "${MINE_PREFLIGHT_PROFILE:-}" ]]; then
  compose_args+=(--profile "${MINE_PREFLIGHT_PROFILE}")
fi
docker compose "${compose_args[@]}" config --quiet
MINE_ENV_FILE="${ENV_FILE}" "${ROOT_DIR}/deploy/tests/java-artifact-isolation.sh"
echo "PASS: Docker、Compose、磁盘、文件句柄、secret、证书和 Compose 配置检查通过"
