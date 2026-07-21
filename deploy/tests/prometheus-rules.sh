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
  MineSpatialEvaluationQueueBacklogHigh
  MineSpatialTooLateRateHigh
  MineSpatialOutboxDead
  MineSpatialStateRecoveryFailures
  MineSpatialReplayTimeout
)
required_metrics=(
  mine_telemetry_location_snapshot_result_total
  mine_gateway_location_ingest_total
  mine_telemetry_location_redis_latest_total
  mine_telemetry_location_quarantine_size
  mine_telemetry_spatial_queue_backlog
  mine_telemetry_spatial_queue_events_total
  mine_telemetry_spatial_outbox_dead
  mine_telemetry_spatial_stage_events_total
  mine_telemetry_spatial_replay_oldest_active_age_seconds
)

operation_alerts=(
  MineOperationQueueBacklogHigh
  MineOperationTooLateRateHigh
  MineOperationStateAdvanceFailures
  MineOperationCandidateSpike
  MineOperationOutboxDead
  MineOperationShiftCloseLeftovers
)
operation_metrics=(
  mine_server_operation_queue_backlog
  mine_server_operation_queue_events_total
  mine_server_operation_stage_events_total
  mine_server_operation_candidates_total
  mine_server_operation_outbox_dead
  mine_server_operation_shift_close_leftovers
)

scene_alerts=(
  MineSceneStorageUnavailable
  MineSceneReadFailureRateHigh
  MineSceneQuarantinedAssetsHigh
  MineSceneSnapshotFailuresPersistent
)
scene_metrics=(
  mine_server_scene_storage_available
  mine_server_scene_operations_total
  mine_server_scene_assets_quarantined
)

for alert in "${required_alerts[@]}"; do
  grep -Fq "alert: ${alert}" "${RULES_FILE}" \
    || { echo "FAIL: 缺少定位告警 ${alert}" >&2; exit 1; }
done

for alert in "${operation_alerts[@]}"; do
  grep -Fq "alert: ${alert}" "${RULES_FILE}" \
    || { echo "FAIL: 缺少生产运营告警 ${alert}" >&2; exit 1; }
done
for metric in "${operation_metrics[@]}"; do
  grep -Fq "${metric}" "${RULES_FILE}" \
    || { echo "FAIL: 生产运营告警未引用指标 ${metric}" >&2; exit 1; }
done
for alert in "${scene_alerts[@]}"; do
  grep -Fq "alert: ${alert}" "${RULES_FILE}" \
    || { echo "FAIL: 缺少三维场景告警 ${alert}" >&2; exit 1; }
done
for metric in "${scene_metrics[@]}"; do
  grep -Fq "${metric}" "${RULES_FILE}" \
    || { echo "FAIL: 三维场景告警未引用指标 ${metric}" >&2; exit 1; }
done
for metric in "${required_metrics[@]}"; do
  grep -Fq "${metric}" "${RULES_FILE}" \
    || { echo "FAIL: 定位告警未引用指标 ${metric}" >&2; exit 1; }
done

location_rules="$(awk '
  /^  - name: mine-(location|spatial)-runtime$/ { capture=1 }
  capture && /^  - name:/ && $0 !~ /mine-(location|spatial)-runtime/ { capture=0 }
  capture { print }
' "${RULES_FILE}")"
if grep -Eq '(^|[,{[:space:]])(site|device|tag|person|vehicle|coordinate|exception|error_text)=' \
    <<<"${location_rules}"; then
  echo "FAIL: 定位告警规则不得使用高基数业务身份或异常文本标签" >&2
  exit 1
fi

operation_rules="$(awk '
  /^  - name: mine-operation-runtime$/ { capture=1 }
  capture && /^  - name:/ && $0 !~ /mine-operation-runtime/ { capture=0 }
  capture { print }
' "${RULES_FILE}")"
if grep -Eq '(^|[,{[:space:]])(organization|site|workOrder|vehicle|driver|person|event|exception|error_text)=' \
    <<<"${operation_rules}"; then
  echo "FAIL: 生产运营告警规则不得使用业务身份或异常文本标签" >&2
  exit 1
fi

scene_rules="$(awk '
  /^  - name: mine-scene-runtime$/ { capture=1 }
  capture && /^  - name:/ && $0 !~ /mine-scene-runtime/ { capture=0 }
  capture { print }
' "${RULES_FILE}")"
if grep -Eq '(^|[,{[:space:]])(organization|site|scene|asset|path|file|user|exception|error_text)=' \
    <<<"${scene_rules}"; then
  echo "FAIL: 三维场景告警规则不得使用业务身份、路径或异常文本标签" >&2
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

echo "PASS: 定位、空间、生产运营与三维场景低基数告警规则和 PromQL 行为测试通过"
