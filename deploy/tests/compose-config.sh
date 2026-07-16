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
