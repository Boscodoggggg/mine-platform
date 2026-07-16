#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/compose-standalone.yml"
ENV_FILE="${ROOT_DIR}/deploy/env/standalone.env.example"

rendered="$(mktemp)"
trap 'rm -f "${rendered}"' EXIT

docker compose --profile observability --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --format json \
  >"${rendered}"

jq -e '.services["redis-exporter"]' "${rendered}" >/dev/null \
  || { echo "FAIL: observability profile 缺少 redis-exporter" >&2; exit 1; }

jq -e '.services["redis-exporter"].environment.REDIS_PASSWORD_FILE == "/run/secrets/redis_exporter_passwords"
  and (.services["redis-exporter"].secrets | any(.source == "redis_exporter_passwords"))' "${rendered}" >/dev/null \
  || { echo "FAIL: redis-exporter 必须使用专用 JSON 密码映射 secret" >&2; exit 1; }

jq -e '.services["mine-server"].environment.MINE_CONFIGURATION_DISTRIBUTION_ENABLED == "true"
  and (.services["mine-server"].secrets | any(.source == "mine_configuration_distribution_token"))
  and .services["mine-gateway"].environment.MINE_GATEWAY_CONFIGURATION_DISTRIBUTION_ENABLED == "true"
  and .services["mine-gateway"].environment.MINE_GATEWAY_CONFIGURATION_SERVER_URL == "http://mine-server:8080"
  and (.services["mine-gateway"].secrets | any(.source == "mine_configuration_distribution_token"))' "${rendered}" >/dev/null \
  || { echo "FAIL: server 与 gateway 必须启用基于 secret 的配置分发闭环" >&2; exit 1; }

jq -e '.services["mine-gateway"].environment.MINE_GATEWAY_CONFIGURATION_CACHE_FILE == "/var/lib/mine/gateway/protocol-snapshots.json"
  and (.services["mine-gateway"].volumes | any(.source == "gateway_configuration_cache" and .target == "/var/lib/mine/gateway"))
  and .services["mine-gateway"].depends_on["mine-gateway-cache-init"].condition == "service_completed_successfully"
  and (.services["mine-gateway-cache-init"].volumes | any(.source == "gateway_configuration_cache" and .target == "/var/lib/mine/gateway"))' "${rendered}" >/dev/null \
  || { echo "FAIL: gateway 必须使用初始化权限后的持久卷保存最后可用配置" >&2; exit 1; }

jq -e '.services.prometheus.depends_on // {} | keys | all(. != "mine-server" and . != "mine-gateway" and . != "mine-telemetry" and . != "rabbitmq" and . != "postgres-exporter" and . != "timescale-exporter" and . != "redis-exporter")' \
  "${rendered}" >/dev/null \
  || { echo "FAIL: Prometheus 不得等待被观测目标健康后才启动" >&2; exit 1; }

for exporter in postgres-exporter timescale-exporter redis-exporter; do
  jq -e --arg exporter "${exporter}" '.services[$exporter].depends_on // {} | length == 0' "${rendered}" >/dev/null \
    || { echo "FAIL: ${exporter} 不得等待被观测数据库健康后才启动" >&2; exit 1; }
done

grep -q 'job_name: redis' "${ROOT_DIR}/deploy/config/prometheus/prometheus.yml" \
  || { echo "FAIL: Prometheus 缺少 redis 抓取任务" >&2; exit 1; }

grep -q 'redis-exporter:9121' "${ROOT_DIR}/deploy/config/prometheus/prometheus.yml" \
  || { echo "FAIL: Redis 抓取任务未指向 redis-exporter" >&2; exit 1; }

echo "PASS: 可观测组件能够独立启动并覆盖 Redis 指标"
