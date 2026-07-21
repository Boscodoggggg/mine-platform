#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/compose-standalone.yml"
ENV_FILE="${ROOT_DIR}/deploy/env/standalone.env.example"

rendered="$(mktemp)"
rendered_tcp_disabled="$(mktemp)"
rendered_tcp_unified="$(mktemp)"
trap 'rm -f "${rendered}" "${rendered_tcp_disabled}" "${rendered_tcp_unified}"' EXIT

docker compose --profile observability --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --format json \
  >"${rendered}"

nginx_config="${ROOT_DIR}/deploy/config/nginx/nginx.conf"
node "${ROOT_DIR}/scripts/generate-demo-scene-assets.mjs" --check

grep -Fq "connect-src 'self' wss://\$host" "${nginx_config}" \
  || { echo "FAIL: Web 和三维资源连接必须限制为同源 HTTPS/WSS" >&2; exit 1; }
grep -Fq "worker-src 'self' blob:" "${nginx_config}" \
  || { echo "FAIL: Cesium Worker 只能从同源静态资源或受控 blob 启动" >&2; exit 1; }
grep -Fq "object-src 'none'" "${nginx_config}" \
  || { echo "FAIL: Web 安全策略必须禁止插件对象执行" >&2; exit 1; }
grep -Fq 'resolver 127.0.0.11 valid=10s ipv6=off;' "${nginx_config}" \
  || { echo "FAIL: Nginx 必须通过 Docker DNS 动态解析后端服务" >&2; exit 1; }
grep -Fq 'server mine-server:8080 resolve;' "${nginx_config}" \
  || { echo "FAIL: Nginx 后端节点必须在容器重建后自动更新地址" >&2; exit 1; }
grep -Fq 'zone mine_server 64k;' "${nginx_config}" \
  || { echo "FAIL: Nginx 动态上游必须使用共享内存区保存解析结果" >&2; exit 1; }

MINE_GATEWAY_TCP_ENABLED=false docker compose --profile observability --env-file "${ENV_FILE}" \
  -f "${COMPOSE_FILE}" config --format json >"${rendered_tcp_disabled}"

MINE_GATEWAY_TCP_MODE=UNIFIED MINE_GATEWAY_TCP_PROTOCOL_KEY= MINE_GATEWAY_TCP_TLS_ENABLED=true \
  docker compose --profile observability --env-file "${ENV_FILE}" \
  -f "${COMPOSE_FILE}" config --format json >"${rendered_tcp_unified}"

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

jq -e '.services["mine-server"].environment.MINE_NOTIFICATION_DATA_KEY_FILE == "/run/secrets/mine_notification_data_key"
  and (.services["mine-server"].secrets | any(.source == "mine_notification_data_key"))
  and (.secrets.mine_notification_data_key.file | endswith("/deploy/secrets/mine_notification_data_key"))' \
  "${rendered}" >/dev/null \
  || { echo "FAIL: mine-server 必须挂载独立通知 AES-256-GCM 数据密钥" >&2; exit 1; }

jq -e '.services.rustfs.image | startswith("rustfs/rustfs:1.0.0-beta.10@sha256:")' \
  "${rendered}" >/dev/null \
  || { echo "FAIL: RustFS 必须固定到已验证的版本和镜像摘要" >&2; exit 1; }

jq -e '.services.rustfs.environment.RUSTFS_CONSOLE_ENABLE == "false"
  and (.services.rustfs.volumes | any(.source == "rustfs_data" and .target == "/data"))
  and .services["mine-server"].environment.MINE_SCENE_MULTIPART_MAX_FILE_SIZE == "2GB"
  and .services["mine-server"].environment.MINE_SCENE_MULTIPART_MAX_REQUEST_SIZE == "2050MB"
  and .services["mine-server"].environment.MINE_SCENE_MULTIPART_FILE_SIZE_THRESHOLD == "0B"
  and .services["mine-server"].environment.MINE_SCENE_MULTIPART_LOCATION == "/var/lib/mine/scene-upload/multipart"
  and .services["mine-server"].environment.MINE_SCENE_STORAGE_ROOT == "/var/lib/mine/scene-upload/workspace"
  and .services["mine-server"].environment.MINE_SCENE_STORAGE_MAX_UPLOAD_BYTES == "2147483648"
  and (.services["mine-server"].volumes | any(.source == "scene_upload_workspace" and .target == "/var/lib/mine/scene-upload"))
  and .services["mine-server"].depends_on["mine-scene-staging-init"].condition == "service_completed_successfully"
  and .services["mine-scene-staging-init"].user == "0:0"
  and (.services["mine-scene-staging-init"].volumes | any(.source == "scene_upload_workspace" and .target == "/var/lib/mine/scene-upload"))
  and .services["mine-server"].environment.MINE_SCENE_STORAGE_TYPE == "s3"
  and .services["mine-server"].environment.MINE_OBJECT_STORAGE_TYPE == "s3"
  and .services["mine-server"].environment.MINE_OBJECT_STORAGE_S3_ENDPOINT == "http://rustfs:9000"
  and .services["mine-server"].environment.MINE_OBJECT_STORAGE_S3_INITIALIZE_BUCKET == "true"
  and .services["mine-server"].depends_on.rustfs.condition == "service_healthy"' \
  "${rendered}" >/dev/null \
  || { echo "FAIL: standalone 必须使用显式上传上限，并通过标准 S3 API 使用健康且持久化的 RustFS" >&2; exit 1; }

grep -Fq 'location: ${MINE_SCENE_MULTIPART_LOCATION:/tmp}' \
  "${ROOT_DIR}/mine-server/src/main/resources/application.yml" \
  || { echo "FAIL: Servlet multipart 必须支持独立暂存目录，禁止大文件依赖公共小容量 /tmp" >&2; exit 1; }

jq -e '.services["mine-telemetry"].environment.MINE_TELEMETRY_STRATEGY_DISTRIBUTION_ENABLED == "true"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_STRATEGY_SERVER_URL == "http://mine-server:8080"
  and (.services["mine-telemetry"].secrets | any(.source == "mine_configuration_distribution_token"))
  and .services["mine-telemetry"].depends_on["mine-server"].condition == "service_healthy"' "${rendered}" >/dev/null \
  || { echo "FAIL: telemetry 必须通过 secret 和内部接口加载策略快照" >&2; exit 1; }

jq -e '.services["mine-telemetry"].environment.MINE_TELEMETRY_RETENTION_ENABLED == "true"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_RETENTION_INSTANCE_ID == "standalone-telemetry"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_RETENTION_BATCH_SIZE == "10000"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_RETENTION_MAX_CYCLES == "10"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_RETENTION_INTERVAL == "5m"' "${rendered}" >/dev/null \
  || { echo "FAIL: standalone telemetry 必须显式启用受控有界数据保留" >&2; exit 1; }

jq -e '.services["mine-telemetry"].environment.MINE_TELEMETRY_AGGREGATION_ENABLED == "true"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_AGGREGATION_INSTANCE_ID == "standalone-telemetry"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_AGGREGATION_BATCH_SIZE == "1000"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_AGGREGATION_MAX_CYCLES == "10"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_AGGREGATION_INTERVAL == "1m"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_AGGREGATION_RECOMPUTE_ENABLED == "true"
  and .services["mine-telemetry"].environment.MINE_TELEMETRY_AGGREGATION_RECOMPUTE_LEASE == "10m"' "${rendered}" >/dev/null \
  || { echo "FAIL: standalone telemetry 必须显式启用受控有界数值聚合和重算消费" >&2; exit 1; }

jq -e '.services["mine-server"].environment.MINE_ALARM_RABBIT_ENABLED == "true"
  and .services["mine-server"].environment.MINE_ALARM_RABBIT_PASSWORD_FILE == "/run/secrets/rabbitmq_server_alarm_password"
  and (.services["mine-server"].secrets | any(.source == "rabbitmq_server_alarm_password"))
  and (.services.rabbitmq.secrets | any(.source == "rabbitmq_server_alarm_password"))' "${rendered}" >/dev/null \
  || { echo "FAIL: mine-server 必须使用独立 secret 启用告警消息消费" >&2; exit 1; }

jq -e '.services["mine-server"].environment.MINE_OPERATION_RUNTIME_RABBIT_ENABLED == "true"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_AUTO_STARTUP == "true"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_PREFETCH == "50"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_ALLOWED_LATENESS == "30s"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_IDLE_FLUSH == "5s"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_LEASE_DURATION == "30s"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_MAX_ATTEMPTS == "8"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_AUTOMATION_INTERVAL == "250ms"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_STEP_TIMEOUT == "2h"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_DRAIN_LIMIT == "100"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_ACCOUNT_USERNAME == "mine-server-operation"
  and .services["mine-server"].environment.MINE_OPERATION_RUNTIME_ACCOUNT_PASSWORD_FILE == "/run/secrets/rabbitmq_server_operation_password"
  and (.services["mine-server"].secrets | any(.source == "rabbitmq_server_operation_password"))
  and (.services.rabbitmq.secrets | any(.source == "rabbitmq_server_operation_password"))' "${rendered}" >/dev/null \
  || { echo "FAIL: mine-server 必须使用独立 secret 启用运营空间事件持久消费" >&2; exit 1; }

jq -e '.services["mine-server"].environment.MINE_OPERATION_OUTBOX_ENABLED == "true"
  and .services["mine-server"].environment.MINE_OPERATION_OUTBOX_CONFIRM_TIMEOUT == "5s"
  and .services["mine-server"].environment.MINE_OPERATION_OUTBOX_LEASE_DURATION == "30s"
  and .services["mine-server"].environment.MINE_OPERATION_OUTBOX_MAX_ATTEMPTS == "8"
  and .services["mine-server"].environment.MINE_OPERATION_OUTBOX_RECOVERY_LIMIT == "500"
  and .services["mine-server"].environment.MINE_OPERATION_OUTBOX_RECOVERY_INTERVAL == "1s"
  and .services["mine-server"].environment.MINE_OPERATION_OUTBOX_RABBIT_USERNAME == "mine-server-operation-outbox"
  and .services["mine-server"].environment.MINE_OPERATION_OUTBOX_RABBIT_PASSWORD_FILE == "/run/secrets/rabbitmq_server_operation_outbox_password"
  and (.services["mine-server"].secrets | any(.source == "rabbitmq_server_operation_outbox_password"))
  and (.services.rabbitmq.secrets | any(.source == "rabbitmq_server_operation_outbox_password"))' "${rendered}" >/dev/null \
  || { echo "FAIL: mine-server 必须使用独立最小权限账号启用运营事件可靠发布" >&2; exit 1; }

grep -Fq '# 关闭后空间事件仍会保留在持久队列，但不会自动形成运营记录。' \
  "${ROOT_DIR}/mine-server/src/main/resources/application.yml" \
  || { echo "FAIL: 应用配置必须说明停用运营消费者的业务影响" >&2; exit 1; }
grep -Fq '# 关闭后运营事实仍会提交，但实时变更将留在 Outbox 等待恢复。' \
  "${ROOT_DIR}/mine-server/src/main/resources/application.yml" \
  || { echo "FAIL: 应用配置必须说明停用运营 Outbox 的业务影响" >&2; exit 1; }

jq -e '.services["mine-server"].environment.MINE_SPATIAL_REPLAY_ENABLED == "true"
  and .services["mine-server"].environment.MINE_SPATIAL_REPLAY_USERNAME == "mine_ts_replay"
  and .services["mine-server"].environment.MINE_SPATIAL_REPLAY_PASSWORD_FILE == "/run/secrets/timescale_replay_password"
  and (.services["mine-server"].secrets | any(.source == "timescale_replay_password"))
  and .services["timescale-reader-bootstrap"].environment.TIMESCALE_REPLAY_USER == "mine_ts_replay"
  and (.services["timescale-reader-bootstrap"].secrets | any(.source == "timescale_replay_password"))' "${rendered}" >/dev/null \
  || { echo "FAIL: 空间重放登记必须使用独立 TimescaleDB 最小权限账号和 secret" >&2; exit 1; }

replay_bootstrap="${ROOT_DIR}/deploy/scripts/timescale-reader-init.sh"
grep -q "GRANT SELECT, INSERT ON TABLE spatial_replay_request" "${replay_bootstrap}" \
  || { echo "FAIL: 空间重放账号未获得重放请求表的精确读写权限" >&2; exit 1; }
grep -q "GRANT SELECT ON TABLE spatial_replay_active_zone_snapshot" \
  "${replay_bootstrap}" \
  || { echo "FAIL: 空间重放账号未获得激活区域快照的精确只读权限" >&2; exit 1; }
if grep -q "GRANT .*ALL TABLES.*replay_user" "${replay_bootstrap}"; then
  echo "FAIL: 空间重放账号不得获得全部时序表权限" >&2
  exit 1
fi

jq -e '.services["mine-gateway"].environment.MINE_GATEWAY_CONFIGURATION_CACHE_FILE == "/var/lib/mine/gateway/protocol-snapshots.json"
  and (.services["mine-gateway"].volumes | any(.source == "gateway_configuration_cache" and .target == "/var/lib/mine/gateway"))
  and .services["mine-gateway"].depends_on["mine-gateway-cache-init"].condition == "service_completed_successfully"
  and (.services["mine-gateway-cache-init"].volumes | any(.source == "gateway_configuration_cache" and .target == "/var/lib/mine/gateway"))' "${rendered}" >/dev/null \
  || { echo "FAIL: gateway 必须使用初始化权限后的持久卷保存最后可用配置" >&2; exit 1; }

grep -Fq 'enabled: ${MINE_GATEWAY_TCP_ENABLED:false}' \
  "${ROOT_DIR}/mine-gateway/src/main/resources/application.yml" \
  || { echo "FAIL: TCP 二进制监听器必须保持应用默认关闭" >&2; exit 1; }

jq -e '.services["mine-gateway"].environment.MINE_GATEWAY_TCP_ENABLED == "true"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_MODE == "LEGACY"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_BIND_HOST == "0.0.0.0"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_PORT == "9000"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_PROTOCOL_KEY == "haul-truck-binary"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_MAX_CONNECTIONS == "10000"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_MAX_CONNECTIONS_PER_IP == "100"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_READ_IDLE_SECONDS == "120"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_TLS_ENABLED == "false"' \
  "${rendered}" >/dev/null \
  || { echo "FAIL: standalone gateway 必须显式启用受控 TCP 二进制监听器" >&2; exit 1; }

jq -e '.services["mine-gateway"].environment.MINE_GATEWAY_TCP_ENABLED == "false"' \
  "${rendered_tcp_disabled}" >/dev/null \
  || { echo "FAIL: standalone gateway 必须允许通过环境变量紧急停用 TCP 监听器" >&2; exit 1; }

jq -e '.services["mine-gateway"].environment.MINE_GATEWAY_TCP_MODE == "UNIFIED"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_PROTOCOL_KEY == ""
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_TLS_ENABLED == "true"' \
  "${rendered_tcp_unified}" >/dev/null \
  || { echo "FAIL: 统一 TCP 入口必须支持清空静态协议键并启用 TLS" >&2; exit 1; }

jq -e '(.services["mine-gateway"].ports | any(.target == 9000 and .published == "9000" and .host_ip == "127.0.0.1"))
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_TLS_CERTIFICATE == "/run/secrets/tcp_tls_certificate"
  and .services["mine-gateway"].environment.MINE_GATEWAY_TCP_TLS_PRIVATE_KEY == "/run/secrets/tcp_tls_private_key"
  and (.services["mine-gateway"].secrets | any(.source == "tls_certificate" and .target == "tcp_tls_certificate"))
  and (.services["mine-gateway"].secrets | any(.source == "tls_private_key" and .target == "tcp_tls_private_key"))' \
  "${rendered}" >/dev/null \
  || { echo "FAIL: TCP 端口和 TLS 文件必须通过受控发布及只读 secret 提供" >&2; exit 1; }

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

rules_file="${ROOT_DIR}/deploy/config/prometheus/rules/platform.yml"
grep -q 'MineGatewayConfigurationLoadFailed' "${rules_file}" \
  || { echo "FAIL: 缺少 Gateway 配置永久失败告警" >&2; exit 1; }
grep -q 'MineGatewayConfigurationRetrying' "${rules_file}" \
  || { echo "FAIL: 缺少 Gateway 配置持续重试告警" >&2; exit 1; }
grep -q 'mine_gateway_configuration_distribution_total' "${rules_file}" \
  || { echo "FAIL: Gateway 配置告警未引用分发结果指标" >&2; exit 1; }
grep -q 'MineGatewayTcpListenerDown' "${rules_file}" \
  || { echo "FAIL: 缺少 TCP 监听器不可用告警" >&2; exit 1; }
grep -q 'MineGatewayTcpConnectionsRejected' "${rules_file}" \
  || { echo "FAIL: 缺少 TCP 连接持续拒绝告警" >&2; exit 1; }
grep -q 'MineGatewayTcpFramesRejected' "${rules_file}" \
  || { echo "FAIL: 缺少 TCP 二进制帧持续拒绝告警" >&2; exit 1; }
grep -q 'MineGatewayTcpPublishFailures' "${rules_file}" \
  || { echo "FAIL: 缺少 TCP 遥测可靠发布失败告警" >&2; exit 1; }
grep -q 'MineGatewayTcpNoSuccessfulTelemetry' "${rules_file}" \
  || { echo "FAIL: 缺少 TCP 长时间无成功遥测告警" >&2; exit 1; }
grep -q 'mine_gateway_tcp_listener_up' "${rules_file}" \
  || { echo "FAIL: TCP 监听告警未引用低基数可用性指标" >&2; exit 1; }
grep -q 'mine_gateway_tcp_connections' "${rules_file}" \
  || { echo "FAIL: TCP 连接告警未引用低基数结果指标" >&2; exit 1; }
grep -q 'mine_gateway_tcp_frames' "${rules_file}" \
  || { echo "FAIL: TCP 帧告警未引用低基数结果指标" >&2; exit 1; }
grep -q 'mine_gateway_tcp_publish_failures' "${rules_file}" \
  || { echo "FAIL: TCP 发布告警未引用低基数失败指标" >&2; exit 1; }
grep -q 'MineTelemetryStrategyLoadFailed' "${rules_file}" \
  || { echo "FAIL: 缺少 Telemetry 策略永久加载失败告警" >&2; exit 1; }
grep -q 'MineTelemetryStrategyRetrying' "${rules_file}" \
  || { echo "FAIL: 缺少 Telemetry 策略持续重试告警" >&2; exit 1; }
grep -q 'mine_telemetry_strategy_distribution_total' "${rules_file}" \
  || { echo "FAIL: Telemetry 策略告警未引用分发结果指标" >&2; exit 1; }
grep -q 'MineTelemetryDataQualitySpike' "${rules_file}" \
  || { echo "FAIL: 缺少数据质量异常突增告警" >&2; exit 1; }
grep -q 'mine_telemetry_quality_rule_hits_total' "${rules_file}" \
  || { echo "FAIL: 数据质量告警未引用规则命中指标" >&2; exit 1; }
grep -q 'MineTelemetryAlarmOutboxBacklog' "${rules_file}" \
  || { echo "FAIL: 缺少告警 Outbox 积压告警" >&2; exit 1; }
grep -q 'mine_telemetry_alarm_outbox_events' "${rules_file}" \
  || { echo "FAIL: Outbox 告警未引用固定状态低基数指标" >&2; exit 1; }
grep -q 'MineTelemetryAlarmOutboxFailed' "${rules_file}" \
  || { echo "FAIL: 缺少告警 Outbox 持续失败告警" >&2; exit 1; }
grep -q 'MineTelemetryAlarmOutboxDead' "${rules_file}" \
  || { echo "FAIL: 缺少告警 Outbox 死信告警" >&2; exit 1; }
grep -q 'MineServerAlarmConsumeFailures' "${rules_file}" \
  || { echo "FAIL: 缺少告警消费失败告警" >&2; exit 1; }
grep -q 'mine_server_alarm_consume_total' "${rules_file}" \
  || { echo "FAIL: 告警消费失败规则未引用消费结果指标" >&2; exit 1; }
grep -q 'MineCriticalActiveAlarmsGrowing' "${rules_file}" \
  || { echo "FAIL: 缺少严重活动告警增长告警" >&2; exit 1; }
grep -q 'mine_server_alarm_active' "${rules_file}" \
  || { echo "FAIL: 严重活动告警规则未引用活动告警指标" >&2; exit 1; }
grep -q 'MineTelemetryRetentionConsecutiveFailures' "${rules_file}" \
  || { echo "FAIL: 缺少数据保留连续失败告警" >&2; exit 1; }
grep -q 'MineTelemetryRetentionNoRecentSuccess' "${rules_file}" \
  || { echo "FAIL: 缺少数据保留长时间无成功告警" >&2; exit 1; }
grep -q 'MineTelemetryRetentionPersistentlyPartial' "${rules_file}" \
  || { echo "FAIL: 缺少数据保留持续未完成告警" >&2; exit 1; }
grep -q 'MineTelemetryRetentionAggregationCoverageBlocked' "${rules_file}" \
  || { echo "FAIL: 缺少聚合覆盖阻塞原始数据清理告警" >&2; exit 1; }
grep -q 'mine_telemetry_retention_aggregation_coverage_blocked_rows' "${rules_file}" \
  || { echo "FAIL: 聚合覆盖阻塞告警未引用低基数阻塞量指标" >&2; exit 1; }
grep -q 'mine_telemetry_retention_consecutive_failures' "${rules_file}" \
  || { echo "FAIL: 数据保留告警未引用低基数失败指标" >&2; exit 1; }
grep -q 'MineTelemetryAggregationConsecutiveFailures' "${rules_file}" \
  || { echo "FAIL: 缺少数值聚合连续失败告警" >&2; exit 1; }
grep -q 'MineTelemetryAggregationNoRecentSuccess' "${rules_file}" \
  || { echo "FAIL: 缺少数值聚合长时间无成功告警" >&2; exit 1; }
grep -q 'MineTelemetryAggregationPersistentlyPartial' "${rules_file}" \
  || { echo "FAIL: 缺少数值聚合持续未完成告警" >&2; exit 1; }
grep -q 'MineTelemetryAggregationLateRowsIgnored' "${rules_file}" \
  || { echo "FAIL: 缺少超窗口迟到遥测告警" >&2; exit 1; }
grep -q 'mine_telemetry_aggregation_consecutive_failures' "${rules_file}" \
  || { echo "FAIL: 数值聚合告警未引用低基数失败指标" >&2; exit 1; }
grep -q 'MineTelemetryAggregationRecomputeConsecutiveFailures' "${rules_file}" \
  || { echo "FAIL: 缺少显式聚合重算连续失败告警" >&2; exit 1; }
grep -q 'MineTelemetryAggregationRecomputeWorkerStuck' "${rules_file}" \
  || { echo "FAIL: 缺少显式聚合重算卡死告警" >&2; exit 1; }
grep -q 'MineTelemetryAggregationRecomputeNoRecentPoll' "${rules_file}" \
  || { echo "FAIL: 缺少显式聚合重算轮询中断告警" >&2; exit 1; }
grep -q 'MineServerAggregationRecomputeClaimableBacklog' "${rules_file}" \
  || { echo "FAIL: 缺少显式聚合重算控制面积压告警" >&2; exit 1; }
grep -q 'MineTelemetryAggregationRecomputeLeaseLost' "${rules_file}" \
  || { echo "FAIL: 缺少显式聚合重算租约转移告警" >&2; exit 1; }
grep -q 'mine_server_aggregation_recompute_claimable_oldest_age_seconds' "${rules_file}" \
  || { echo "FAIL: 重算积压告警未引用低基数最老可认领年龄" >&2; exit 1; }
if grep -Eq 'productId|releaseId|deviceId|groupId' "${rules_file}"; then
  echo "FAIL: Prometheus 规则不得依赖高基数业务标识标签" >&2
  exit 1
fi

definitions_file="${ROOT_DIR}/deploy/config/rabbitmq/definitions.json"
jq -e '[.exchanges[] | select(.vhost == "/mine-platform" and .name == "mine.business.x" and .type == "topic" and .durable == true)] | length == 1' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少持久业务事实交换机" >&2; exit 1; }
jq -e '[.queues[] | select(.vhost == "/mine-platform" and .name == "mine.server.alarm-event.q"
  and .durable == true and .arguments["x-queue-type"] == "quorum"
  and .arguments["x-dead-letter-exchange"] == "mine.dlx")] | length == 1' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少带死信的 quorum 告警队列" >&2; exit 1; }
jq -e '[.bindings[] | select(.vhost == "/mine-platform" and .source == "mine.business.x"
  and .destination == "mine.server.alarm-event.q" and .routing_key == "alarm.*")] | length == 1' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少告警业务事实绑定" >&2; exit 1; }
jq -e '[.queues[] | select(.vhost == "/mine-device" and .name == "mine.gateway.location-uplink.q"
  and .durable == true and .arguments["x-queue-type"] == "quorum"
  and .arguments["x-dead-letter-exchange"] == "mine.device.dlx")] | length == 1' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少带死信的原始定位上行队列" >&2; exit 1; }
jq -e '[.bindings[] | select(.vhost == "/mine-device" and .source == "amq.topic"
  and .destination == "mine.gateway.location-uplink.q"
  and .routing_key == "mine.v1.*.*.up.location")] | length == 1' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少原始定位精确上行绑定" >&2; exit 1; }
jq -e '[.exchanges[] | select(.vhost == "/mine-platform" and .name == "mine.location-config.x"
  and .type == "topic" and .durable == true)] | length == 1' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少持久定位运行快照交换机" >&2; exit 1; }
jq -e '[.queues[] | select(.vhost == "/mine-platform"
  and (.name == "mine.location.q" or .name == "mine.telemetry.location-config.q")
  and .durable == true and .arguments["x-queue-type"] == "quorum"
  and .arguments["x-dead-letter-exchange"] == "mine.dlx")] | length == 2' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少带死信的定位观测或快照 quorum 队列" >&2; exit 1; }
jq -e '([.bindings[] | select(.vhost == "/mine-platform" and .source == "mine.telemetry.x"
    and .destination == "mine.location.q" and .routing_key == "location.observation")] | length == 1)
  and ([.bindings[] | select(.vhost == "/mine-platform" and .source == "mine.location-config.x"
    and .destination == "mine.telemetry.location-config.q"
    and .routing_key == "location.runtime.#")] | length == 1)' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 定位观测或快照必须使用精确独立绑定" >&2; exit 1; }
jq -e '[.queues[] | select(.vhost == "/mine-platform"
  and .name == "mine.server.operation-spatial.q"
  and .durable == true and .arguments["x-queue-type"] == "quorum"
  and .arguments["x-dead-letter-exchange"] == "mine.dlx")] | length == 1' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少带死信的运营空间事件持久队列" >&2; exit 1; }
jq -e '[.bindings[] | select(.vhost == "/mine-platform" and .source == "mine.realtime.x"
  and .destination == "mine.server.operation-spatial.q"
  and .routing_key == "realtime.spatial.#")] | length == 1' \
  "${definitions_file}" >/dev/null \
  || { echo "FAIL: RabbitMQ 缺少运营空间事件独立绑定" >&2; exit 1; }

bootstrap_file="${ROOT_DIR}/deploy/scripts/rabbitmq-bootstrap.sh"
grep -q 'upsert_user "mine-server-alarm"' "${bootstrap_file}" \
  || { echo "FAIL: RabbitMQ 启动脚本缺少告警消费账号" >&2; exit 1; }
grep -q 'upsert_user "mine-server-operation"' "${bootstrap_file}" \
  || { echo "FAIL: RabbitMQ 启动脚本缺少运营空间消费账号" >&2; exit 1; }
grep -q 'upsert_user "mine-server-operation-outbox"' "${bootstrap_file}" \
  || { echo "FAIL: RabbitMQ 启动脚本缺少运营事件发布账号" >&2; exit 1; }
grep -q "mine\\\\.business\\\\.x" "${bootstrap_file}" \
  || { echo "FAIL: telemetry 账号未获得业务事实交换机写权限" >&2; exit 1; }
grep -q "mine\\\\.server\\\\.alarm-event\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: server 告警账号未限制为告警队列只读" >&2; exit 1; }
grep -q "mine\\\\.server\\\\.operation-spatial\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: server 运营账号未限制为运营空间队列只读" >&2; exit 1; }
grep -Fq "  '^$' '^mine\\.realtime\\.x$' '^$'" "${bootstrap_file}" \
  || { echo "FAIL: server 运营事件发布账号未获得实时交换机写权限" >&2; exit 1; }
grep -q "mine\\\\.gateway\\\\.location-uplink\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: gateway device 账号未获得原始定位队列只读权限" >&2; exit 1; }
grep -q "mine\\\\.location\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: telemetry 账号未获得定位观测队列只读权限" >&2; exit 1; }
grep -q "mine\\\\.telemetry\\\\.location-config\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: telemetry 账号未获得定位快照队列只读权限" >&2; exit 1; }
grep -q "mine\\\\.location-config\\\\.x" "${bootstrap_file}" \
  || { echo "FAIL: server command 账号未获得定位快照交换机写权限" >&2; exit 1; }

echo "PASS: 部署配置覆盖可靠拓扑、受控 TCP、聚合与保留和低基数告警规则"
