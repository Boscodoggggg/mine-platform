#!/usr/bin/env bash
set -euo pipefail

export SEED_USERNAME="$(cat /run/secrets/rabbitmq_admin_username)"
export SEED_USER_PASSWORD="$(cat /run/secrets/rabbitmq_admin_password)"

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

gateway_device_password="$(cat /run/secrets/rabbitmq_gateway_device_password)"
gateway_platform_password="$(cat /run/secrets/rabbitmq_gateway_platform_password)"
telemetry_password="$(cat /run/secrets/rabbitmq_telemetry_password)"
server_realtime_password="$(cat /run/secrets/rabbitmq_server_realtime_password)"
server_command_password="$(cat /run/secrets/rabbitmq_server_command_password)"
upsert_user "mine-gateway-device" "${gateway_device_password}"
upsert_user "mine-gateway-platform" "${gateway_platform_password}"
upsert_user "mine-telemetry" "${telemetry_password}"
upsert_user "mine-server-realtime" "${server_realtime_password}"
upsert_user "mine-server-command" "${server_command_password}"
rabbitmqctl -q set_permissions -p /mine-device "mine-gateway-device" \
  '^$' '^$' '^mine\.gateway\.uplink\.q$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-gateway-platform" \
  '^$' '^mine\.telemetry\.x$' '^$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-telemetry" \
  '^$' '^mine\.realtime\.x$' '^mine\.telemetry\.q$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-server-realtime" \
  '^(mine\.realtime\.x|mine\.realtime\.server\.[A-Za-z0-9_-]+\.q)$' \
  '^(mine\.realtime\.x|mine\.realtime\.server\.[A-Za-z0-9_-]+\.q)$' \
  '^(mine\.realtime\.x|mine\.realtime\.server\.[A-Za-z0-9_-]+\.q)$'
rabbitmqctl -q set_permissions -p /mine-platform "mine-server-command" \
  '^$' '^mine\.command\.x$' '^$'
unset gateway_device_password gateway_platform_password telemetry_password server_realtime_password server_command_password
touch /tmp/mine-rabbitmq-bootstrap-complete

wait "${broker_pid}"
