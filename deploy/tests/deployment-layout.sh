#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_DIR="${ROOT_DIR}/deploy"
COMPOSE_FILE="${DEPLOY_DIR}/docker-compose.yml"
ENV_FILE="${DEPLOY_DIR}/.env.example"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

[[ -f "${COMPOSE_FILE}" ]] || fail "部署根目录缺少 docker-compose.yml"
[[ -f "${ENV_FILE}" ]] || fail "部署根目录缺少 .env.example"
[[ -d "${DEPLOY_DIR}/backend" ]] || fail "部署根目录缺少 backend 目录"
[[ -d "${DEPLOY_DIR}/frontend" ]] || fail "部署根目录缺少 frontend 目录"
[[ -d "${DEPLOY_DIR}/certs" ]] || fail "部署根目录缺少 certs 目录"

rendered="$(mktemp)"
trap 'rm -f "${rendered}"' EXIT
docker compose --profile observability --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" \
  config --format json >"${rendered}"

jq -e 'has("secrets") | not' "${rendered}" >/dev/null \
  || fail "密码必须来自 env，Compose 不得再声明 secrets"

for service in mine-server mine-gateway mine-telemetry; do
  jq -e --arg service "${service}" '
    any(
      .services[$service].volumes[]?;
      .type == "bind"
      and .target == "/opt/mine/source/app.jar"
      and .read_only == true
      and (.source | endswith(".jar"))
    )
  ' "${rendered}" >/dev/null || fail "${service} 必须只读挂载独立 JAR"
done

jq -e '
  any(
    .services.nginx.volumes[]?;
    .type == "bind"
    and .target == "/usr/share/nginx/html"
    and .read_only == true
    and (.source | endswith("/deploy/frontend/dist"))
  )
  and any(
    .services.nginx.volumes[]?;
    .type == "bind"
    and .target == "/etc/mine/certs"
    and .read_only == true
    and (.source | endswith("/deploy/certs"))
  )
' "${rendered}" >/dev/null || fail "Nginx 必须只读挂载前端 dist 和证书目录"

jq -e '
  (.services["mine-gateway"].volumes | any(
    .type == "volume"
    and .source == "gateway_tls"
    and .target == "/etc/mine/certs"
    and .read_only == true
  ))
  and .services["mine-gateway"].depends_on["mine-gateway-tls-init"].condition == "service_completed_successfully"
  and (.services["mine-gateway-tls-init"].volumes | any(
    .type == "bind"
    and .target == "/opt/mine/source-certs"
    and .read_only == true
  ))
  and (.services["mine-gateway-tls-init"].volumes | any(
    .type == "volume"
    and .source == "gateway_tls"
    and .target == "/etc/mine/certs"
  ))
  and (.services.rabbitmq.volumes | any(
    .type == "volume"
    and .source == "rabbitmq_tls"
    and .target == "/etc/mine/certs"
    and .read_only == true
  ))
  and .services.rabbitmq.depends_on["rabbitmq-tls-init"].condition == "service_completed_successfully"
' "${rendered}" >/dev/null || fail "非 root 服务必须从专属只读卷读取经过权限收敛的 TLS 文件"

jq -e '
  .services.postgres.environment.POSTGRES_PASSWORD == "change-me-postgres"
  and .services.timescale.environment.POSTGRES_PASSWORD == "change-me-timescale"
  and .services.redis.environment.REDIS_PASSWORD == "change-me-redis"
  and .services.rabbitmq.environment.RABBITMQ_ADMIN_PASSWORD == "change-me-rabbitmq-admin"
  and .services.rustfs.environment.RUSTFS_ACCESS_KEY == "change-me-rustfs-access-key"
  and .services.rustfs.environment.RUSTFS_SECRET_KEY == "change-me-rustfs-secret-key"
  and .services["mine-server"].environment.MINE_OBJECT_STORAGE_S3_ACCESS_KEY == "change-me-rustfs-access-key"
  and .services["mine-server"].environment.MINE_OBJECT_STORAGE_S3_SECRET_KEY == "change-me-rustfs-secret-key"
  and .services.grafana.environment.GF_SECURITY_ADMIN_PASSWORD == "change-me-grafana"
' "${rendered}" >/dev/null || fail "基础设施密码必须从 env 直接注入"

grep -q '^MINE_SERVER_JAR_PATH=' "${ENV_FILE}" || fail ".env.example 缺少 server JAR 路径"
grep -q '^MINE_WEB_DIST_PATH=' "${ENV_FILE}" || fail ".env.example 缺少前端 dist 路径"
grep -q '^POSTGRES_PASSWORD=' "${ENV_FILE}" || fail ".env.example 缺少 PostgreSQL 密码"
grep -q '^MINE_PERSON_DATA_KEY=' "${ENV_FILE}" || fail ".env.example 缺少人员数据密钥"

for script in migrate-legacy-env.sh prepare-artifacts.sh stage-tls-certificates.sh start.sh stop.sh restart.sh status.sh; do
  [[ -x "${DEPLOY_DIR}/scripts/${script}" ]] || fail "缺少可执行部署脚本 scripts/${script}"
done

echo "PASS: deploy 目录、挂载制品、证书和 env 凭据布局正确"
