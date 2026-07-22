#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATION_SCRIPT="${ROOT_DIR}/deploy/scripts/migrate-legacy-env.sh"
[[ -x "${MIGRATION_SCRIPT}" ]] || {
  echo "FAIL: 缺少可执行旧部署配置迁移脚本" >&2
  exit 1
}

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
fixture="${work_dir}/fixture"
mkdir -p "${fixture}/deploy/env" "${fixture}/deploy/secrets" "${fixture}/deploy/certs"
cp "${ROOT_DIR}/deploy/.env.example" "${fixture}/deploy/.env.example"
printf 'MINE_HTTPS_PORT=9443\nMINE_TIMEZONE=Asia/Shanghai\nMINE_WEB_DIST_PATH=../../mine-web/dist\nMINE_RUSTFS_ACCESS_KEY=mine-rustfs-dev\nMINE_RUSTFS_SECRET_KEY=mine-rustfs-dev-secret\n' \
  >"${fixture}/deploy/env/standalone.env"

declare -a files=(
  postgres_password timescale_password timescale_reader_password timescale_replay_password redis_password
  rabbitmq_admin_username rabbitmq_admin_password rabbitmq_gateway_device_password
  rabbitmq_gateway_platform_password rabbitmq_telemetry_password rabbitmq_server_realtime_password
  rabbitmq_server_command_password rabbitmq_server_alarm_password rabbitmq_server_operation_password
  rabbitmq_server_operation_outbox_password mine_configuration_distribution_token mine_super_admin_password
  mine_person_data_key mine_notification_data_key grafana_admin_password
)
for file in "${files[@]}"; do
  printf 'fixture-%s\n' "${file}" >"${fixture}/deploy/secrets/${file}"
done
printf 'fixture certificate\n' >"${fixture}/deploy/secrets/tls.crt"
printf 'fixture private key\n' >"${fixture}/deploy/secrets/tls.key"

MINE_ROOT_DIR="${fixture}" "${MIGRATION_SCRIPT}"

target="${fixture}/deploy/.env"
[[ -s "${target}" ]] || { echo "FAIL: 未生成 deploy/.env" >&2; exit 1; }
grep -q "^MINE_HTTPS_PORT='9443'$" "${target}" || { echo "FAIL: 未保留旧端口配置" >&2; exit 1; }
grep -q "^MINE_WEB_DIST_PATH='./frontend/dist'$" "${target}" || { echo "FAIL: 未切换前端挂载路径" >&2; exit 1; }
grep -q "^POSTGRES_PASSWORD='fixture-postgres_password'$" "${target}" || { echo "FAIL: 未迁移 PostgreSQL 密码" >&2; exit 1; }
grep -q "^MINE_NOTIFICATION_DATA_KEY='fixture-mine_notification_data_key'$" "${target}" || { echo "FAIL: 未迁移通知数据密钥" >&2; exit 1; }
! grep -Eq "^MINE_RUSTFS_(ACCESS_KEY|SECRET_KEY)='(mine-rustfs-dev|mine-rustfs-dev-secret|change-me-[^']+)'$" "${target}" \
  || { echo "FAIL: 迁移后仍保留 RustFS 公开开发凭据或占位值" >&2; exit 1; }
[[ -s "${fixture}/deploy/certs/tls.crt" && -s "${fixture}/deploy/certs/tls.key" ]] \
  || { echo "FAIL: 未迁移 TLS 文件" >&2; exit 1; }
[[ -s "${fixture}/deploy/secrets/postgres_password" ]] || { echo "FAIL: 迁移不应删除旧凭据" >&2; exit 1; }

env_mode="$(stat -f '%Lp' "${target}" 2>/dev/null || stat -c '%a' "${target}")"
key_mode="$(stat -f '%Lp' "${fixture}/deploy/certs/tls.key" 2>/dev/null || stat -c '%a' "${fixture}/deploy/certs/tls.key")"
[[ "${env_mode}" == "600" && "${key_mode}" == "600" ]] \
  || { echo "FAIL: .env 或 TLS 私钥权限不正确" >&2; exit 1; }

if MINE_ROOT_DIR="${fixture}" "${MIGRATION_SCRIPT}" >/dev/null 2>&1; then
  echo "FAIL: 未显式 --force 时不应覆盖已有 deploy/.env" >&2
  exit 1
fi

echo "PASS: 旧 env/secret 可无损迁移到 deploy/.env 与 certs"
