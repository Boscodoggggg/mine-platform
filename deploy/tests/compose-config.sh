#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/compose-standalone.yml"
ENV_FILE="${ROOT_DIR}/deploy/env/standalone.env.example"

rendered="$(mktemp)"
rendered_tcp_disabled="$(mktemp)"
trap 'rm -f "${rendered}" "${rendered_tcp_disabled}"' EXIT

docker compose --profile observability --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --format json \
  >"${rendered}"

MINE_GATEWAY_TCP_ENABLED=false docker compose --profile observability --env-file "${ENV_FILE}" \
  -f "${COMPOSE_FILE}" config --format json >"${rendered_tcp_disabled}"

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

jq -e '.services["mine-gateway"].environment.MINE_GATEWAY_CONFIGURATION_CACHE_FILE == "/var/lib/mine/gateway/protocol-snapshots.json"
  and (.services["mine-gateway"].volumes | any(.source == "gateway_configuration_cache" and .target == "/var/lib/mine/gateway"))
  and .services["mine-gateway"].depends_on["mine-gateway-cache-init"].condition == "service_completed_successfully"
  and (.services["mine-gateway-cache-init"].volumes | any(.source == "gateway_configuration_cache" and .target == "/var/lib/mine/gateway"))' "${rendered}" >/dev/null \
  || { echo "FAIL: gateway 必须使用初始化权限后的持久卷保存最后可用配置" >&2; exit 1; }

grep -Fq 'enabled: ${MINE_GATEWAY_TCP_ENABLED:false}' \
  "${ROOT_DIR}/mine-gateway/src/main/resources/application.yml" \
  || { echo "FAIL: TCP 二进制监听器必须保持应用默认关闭" >&2; exit 1; }

jq -e '.services["mine-gateway"].environment.MINE_GATEWAY_TCP_ENABLED == "true"
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

bootstrap_file="${ROOT_DIR}/deploy/scripts/rabbitmq-bootstrap.sh"
grep -q 'upsert_user "mine-server-alarm"' "${bootstrap_file}" \
  || { echo "FAIL: RabbitMQ 启动脚本缺少告警消费账号" >&2; exit 1; }
grep -q "mine\\\\.business\\\\.x" "${bootstrap_file}" \
  || { echo "FAIL: telemetry 账号未获得业务事实交换机写权限" >&2; exit 1; }
grep -q "mine\\\\.server\\\\.alarm-event\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: server 告警账号未限制为告警队列只读" >&2; exit 1; }
grep -q "mine\\\\.gateway\\\\.location-uplink\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: gateway device 账号未获得原始定位队列只读权限" >&2; exit 1; }
grep -q "mine\\\\.location\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: telemetry 账号未获得定位观测队列只读权限" >&2; exit 1; }
grep -q "mine\\\\.telemetry\\\\.location-config\\\\.q" "${bootstrap_file}" \
  || { echo "FAIL: telemetry 账号未获得定位快照队列只读权限" >&2; exit 1; }
grep -q "mine\\\\.location-config\\\\.x" "${bootstrap_file}" \
  || { echo "FAIL: server command 账号未获得定位快照交换机写权限" >&2; exit 1; }

echo "PASS: 部署配置覆盖可靠拓扑、受控 TCP、聚合与保留和低基数告警规则"
