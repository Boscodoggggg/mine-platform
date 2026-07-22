#!/usr/bin/env bash
set -euo pipefail

export SEED_USERNAME="${RABBITMQ_ADMIN_USERNAME:?必须配置 RABBITMQ_ADMIN_USERNAME}"
export SEED_USER_PASSWORD="${RABBITMQ_ADMIN_PASSWORD:?必须配置 RABBITMQ_ADMIN_PASSWORD}"

broker_pid=""

shutdown_broker() {
  if [[ -n "${broker_pid}" ]] && kill -0 "${broker_pid}" 2>/dev/null; then
    rabbitmqctl -q shutdown || kill -TERM "${broker_pid}"
    wait "${broker_pid}" || true
  fi
}

trap shutdown_broker TERM INT

rabbitmq-server &
broker_pid=$!

users=""
for _ in $(seq 1 60); do
  if users="$(rabbitmqctl -q list_users 2>/dev/null)"; then
    break
  fi
  if ! kill -0 "${broker_pid}" 2>/dev/null; then
    wait "${broker_pid}"
    exit $?
  fi
  sleep 1
done

if [[ -z "${users}" ]] && ! rabbitmqctl -q list_users >/dev/null 2>&1; then
  echo "RabbitMQ 未在 60 秒内完成启动" >&2
  shutdown_broker
  exit 1
fi

upsert_user() {
  local username="$1"
  local password="$2"
  if rabbitmqctl -q list_users | awk -v expected="${username}" '$1 == expected { found = 1 } END { exit !found }'; then
    rabbitmqctl -q change_password "${username}" "${password}" >/dev/null 2>&1
  else
    rabbitmqctl -q add_user "${username}" "${password}" >/dev/null 2>&1
  fi
}

upsert_user "${SEED_USERNAME}" "${SEED_USER_PASSWORD}"
rabbitmqctl -q set_user_tags "${SEED_USERNAME}" administrator
rabbitmqctl -q import_definitions /etc/rabbitmq/definitions.json
rabbitmqctl -q set_permissions -p /mine-platform "${SEED_USERNAME}" '.*' '.*' '.*'
rabbitmqctl -q set_permissions -p /mine-device "${SEED_USERNAME}" '.*' '.*' '.*'

legacy_command_messages="$(rabbitmqctl -q list_queues -p /mine-platform name messages \
  | awk '$1 == "mine.command.q" { print $2 }')"
if [[ -n "${legacy_command_messages}" ]]; then
  if [[ "${legacy_command_messages}" != "0" ]]; then
    echo "旧命令队列 mine.command.q 尚有 ${legacy_command_messages} 条消息，请先导出并重放后再升级" >&2
    shutdown_broker
    exit 1
  fi
  rabbitmqctl -q delete_queue -p /mine-platform mine.command.q >/dev/null
fi

gateway_device_password="${RABBITMQ_GATEWAY_DEVICE_PASSWORD:?必须配置 RABBITMQ_GATEWAY_DEVICE_PASSWORD}"
gateway_platform_password="${RABBITMQ_GATEWAY_PLATFORM_PASSWORD:?必须配置 RABBITMQ_GATEWAY_PLATFORM_PASSWORD}"
telemetry_password="${RABBITMQ_TELEMETRY_PASSWORD:?必须配置 RABBITMQ_TELEMETRY_PASSWORD}"
server_realtime_password="${RABBITMQ_SERVER_REALTIME_PASSWORD:?必须配置 RABBITMQ_SERVER_REALTIME_PASSWORD}"
server_command_password="${RABBITMQ_SERVER_COMMAND_PASSWORD:?必须配置 RABBITMQ_SERVER_COMMAND_PASSWORD}"
server_alarm_password="${RABBITMQ_SERVER_ALARM_PASSWORD:?必须配置 RABBITMQ_SERVER_ALARM_PASSWORD}"
server_operation_password="${RABBITMQ_SERVER_OPERATION_PASSWORD:?必须配置 RABBITMQ_SERVER_OPERATION_PASSWORD}"
server_operation_outbox_password="${RABBITMQ_SERVER_OPERATION_OUTBOX_PASSWORD:?必须配置 RABBITMQ_SERVER_OPERATION_OUTBOX_PASSWORD}"
upsert_user "mine-gateway-device" "${gateway_device_password}"
upsert_user "mine-gateway-platform" "${gateway_platform_password}"
upsert_user "mine-telemetry" "${telemetry_password}"
upsert_user "mine-server-realtime" "${server_realtime_password}"
upsert_user "mine-server-command" "${server_command_password}"
upsert_user "mine-server-alarm" "${server_alarm_password}"
upsert_user "mine-server-operation" "${server_operation_password}"
upsert_user "mine-server-operation-outbox" "${server_operation_outbox_password}"
rabbitmqctl -q set_permissions -p /mine-device "mine-gateway-device" \
  '^$' '^amq\.topic$' \
  '^(mine\.gateway\.uplink\.q|mine\.gateway\.location-uplink\.q|mine\.gateway\.command-ack\.q)$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-gateway-platform" \
  '^$' '^(mine\.telemetry\.x|mine\.command\.x)$' '^(mine\.gateway\.command\.q|mine\.config\.q)$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-telemetry" \
  '^$' '^(mine\.realtime\.x|mine\.business\.x)$' \
  '^(mine\.telemetry\.q|mine\.location\.q|mine\.telemetry\.strategy\.q|mine\.telemetry\.location-config\.q)$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-server-realtime" \
  '^(mine\.realtime\.x|mine\.realtime\.server\.[A-Za-z0-9_-]+\.q)$' \
  '^(mine\.realtime\.x|mine\.realtime\.server\.[A-Za-z0-9_-]+\.q)$' \
  '^(mine\.realtime\.x|mine\.realtime\.server\.[A-Za-z0-9_-]+\.q)$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-server-command" \
  '^$' '^(mine\.command\.x|mine\.config\.x|mine\.strategy\.x|mine\.location-config\.x)$' \
  '^mine\.server\.command-status\.q$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-server-alarm" \
  '^$' '^$' '^mine\.server\.alarm-event\.q$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-server-operation" \
  '^$' '^$' '^mine\.server\.operation-spatial\.q$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-server-operation-outbox" \
  '^$' '^mine\.realtime\.x$' '^$'
unset legacy_command_messages gateway_device_password gateway_platform_password telemetry_password server_realtime_password server_command_password server_alarm_password server_operation_password server_operation_outbox_password
touch /tmp/mine-rabbitmq-bootstrap-complete

wait "${broker_pid}"
