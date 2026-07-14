#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${MINE_COMPOSE_FILE:-${ROOT_DIR}/deploy/compose/compose-standalone.yml}"
ENV_FILE="${MINE_ENV_FILE:-${ROOT_DIR}/deploy/env/standalone.env}"

[[ -f "${ENV_FILE}" ]] || { echo "FAIL: 缺少环境变量文件：${ENV_FILE}" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

compose=(docker compose --profile observability --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
services=(prometheus grafana loki alloy postgres-exporter timescale-exporter)
deadline=$((SECONDS + ${MINE_OBSERVABILITY_TIMEOUT_SECONDS:-180}))
while (( SECONDS < deadline )); do
  all_ready=true
  for service in "${services[@]}"; do
    container_id="$("${compose[@]}" ps --quiet "${service}")"
    if [[ -z "${container_id}" ]]; then
      all_ready=false
      continue
    fi
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
    if [[ "${health}" != "healthy" && "${health}" != "running" ]]; then
      [[ "${health}" != "unhealthy" && "${health}" != "exited" && "${health}" != "dead" ]] \
        || { echo "FAIL: ${service} 状态为 ${health}" >&2; exit 1; }
      all_ready=false
    fi
  done
  [[ "${all_ready}" == "true" ]] && break
  sleep 5
done
[[ "${all_ready:-false}" == "true" ]] || { echo "FAIL: 可观测服务未在限定时间内就绪" >&2; exit 1; }

jobs=(mine-server mine-gateway mine-telemetry rabbitmq postgres timescale)
targets=""
targets_ready=false
for _ in $(seq 1 20); do
  targets="$(curl --fail --silent --show-error "http://127.0.0.1:${MINE_PROMETHEUS_PORT:-9090}/api/v1/targets")"
  targets_ready=true
  for job in "${jobs[@]}"; do
    grep -q "\"scrapePool\":\"${job}\".*\"health\":\"up\"" <<<"${targets}" || targets_ready=false
  done
  [[ "${targets_ready}" == "true" ]] && break
  sleep 3
done
for job in "${jobs[@]}"; do
  grep -q "\"scrapePool\":\"${job}\"" <<<"${targets}" \
    || { echo "FAIL: Prometheus 缺少 ${job} target" >&2; exit 1; }
  grep -q "\"scrapePool\":\"${job}\".*\"health\":\"up\"" <<<"${targets}" \
    || { echo "FAIL: Prometheus ${job} target 未达到 up" >&2; exit 1; }
done

curl --fail --silent --show-error --retry 20 --retry-all-errors --retry-delay 2 \
  "http://127.0.0.1:${MINE_GRAFANA_PORT:-3000}/api/health" \
  | grep -q '"database": "ok"'
secrets_dir="$(cd "${ROOT_DIR}/deploy/compose" && cd "${MINE_SECRETS_DIR:-../secrets}" && pwd)"
grafana_password="$(cat "${secrets_dir}/grafana_admin_password")"
curl --fail --silent --show-error \
  --user "${MINE_GRAFANA_ADMIN_USER:-mine-admin}:${grafana_password}" \
  --get --data-urlencode 'query=智慧矿山平台总览' \
  "http://127.0.0.1:${MINE_GRAFANA_PORT:-3000}/api/search" \
  | grep -q '"uid":"mine-platform-overview"'
curl --fail --silent --show-error --retry 20 --retry-all-errors --retry-delay 2 \
  "http://127.0.0.1:${MINE_LOKI_PORT:-3100}/ready" | grep -q ready

trace_headers="$(mktemp)"
trap 'rm -f "${trace_headers}"' EXIT
curl --fail --silent --show-error --insecure -D "${trace_headers}" -o /dev/null \
  "https://127.0.0.1:${MINE_HTTPS_PORT:-443}/api/v1/system/status"
trace_id="$(awk '/^[Xx]-[Rr]equest-[Ii][Dd]:/ { gsub("\r", "", $2); print $2 }' "${trace_headers}")"
[[ -n "${trace_id}" ]] || { echo "FAIL: HTTPS 响应缺少 X-Request-Id" >&2; exit 1; }

for _ in $(seq 1 20); do
  logs="$(curl --fail --silent --show-error --get \
    --data-urlencode "query={service_name=\"mine-standalone-nginx-1\"} |= \"${trace_id}\"" \
    "http://127.0.0.1:${MINE_LOKI_PORT:-3100}/loki/api/v1/query_range")"
  if grep -q "${trace_id}" <<<"${logs}"; then
    echo "PASS: Prometheus targets、Grafana、Loki、Alloy 与 traceId 日志查询均正常"
    exit 0
  fi
  sleep 2
done

echo "FAIL: 40 秒内未在 Loki 查询到 traceId=${trace_id}" >&2
exit 1
