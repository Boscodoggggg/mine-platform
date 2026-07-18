#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROMETHEUS_DIR="${ROOT_DIR}/deploy/config/prometheus"
RULES_FILE="${PROMETHEUS_DIR}/rules/platform.yml"
TEST_FILE="${PROMETHEUS_DIR}/tests/platform.test.yml"
PROMETHEUS_IMAGE="${MINE_PROMETHEUS_IMAGE:-prom/prometheus:v3.12.0}"

required_alerts=(
  MineLocationSnapshotFailuresPersistent
  MineLocationQueueBacklogHigh
  MineLocationIngressRejectionRateHigh
  MineLocationRedisLatestFailuresPersistent
  MineLocationQuarantineNearLimit
)
required_metrics=(
  mine_telemetry_location_snapshot_result_total
  mine_gateway_location_ingest_total
  mine_telemetry_location_redis_latest_total
  mine_telemetry_location_quarantine_size
)

for alert in "${required_alerts[@]}"; do
  grep -Fq "alert: ${alert}" "${RULES_FILE}" \
    || { echo "FAIL: 缺少定位告警 ${alert}" >&2; exit 1; }
done
for metric in "${required_metrics[@]}"; do
  grep -Fq "${metric}" "${RULES_FILE}" \
    || { echo "FAIL: 定位告警未引用指标 ${metric}" >&2; exit 1; }
done

location_rules="$(awk '
  /^  - name: mine-location-runtime$/ { capture=1 }
  capture && /^  - name:/ && $0 !~ /mine-location-runtime/ { exit }
  capture { print }
' "${RULES_FILE}")"
if grep -Eq '(^|[,{[:space:]])(site|device|tag|person|vehicle|coordinate|exception|error_text)=' \
    <<<"${location_rules}"; then
  echo "FAIL: 定位告警规则不得使用高基数业务身份或异常文本标签" >&2
  exit 1
fi

if command -v promtool >/dev/null 2>&1; then
  promtool check rules "${RULES_FILE}"
  (cd "${PROMETHEUS_DIR}/tests" && promtool test rules "$(basename "${TEST_FILE}")")
else
  docker run --rm --entrypoint promtool \
    -v "${PROMETHEUS_DIR}:/workspace:ro" \
    -w /workspace/tests \
    "${PROMETHEUS_IMAGE}" \
    check rules /workspace/rules/platform.yml
  docker run --rm --entrypoint promtool \
    -v "${PROMETHEUS_DIR}:/workspace:ro" \
    -w /workspace/tests \
    "${PROMETHEUS_IMAGE}" \
    test rules platform.test.yml
fi

echo "PASS: 定位低基数告警规则和 PromQL 行为测试通过"
