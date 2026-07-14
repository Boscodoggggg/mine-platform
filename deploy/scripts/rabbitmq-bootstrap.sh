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

if awk -v username="${SEED_USERNAME}" '$1 == username { found = 1 } END { exit !found }' <<<"${users}"; then
  rabbitmqctl -q change_password "${SEED_USERNAME}" "${SEED_USER_PASSWORD}" >/dev/null 2>&1
else
  rabbitmqctl -q add_user "${SEED_USERNAME}" "${SEED_USER_PASSWORD}" >/dev/null 2>&1
fi
rabbitmqctl -q set_user_tags "${SEED_USERNAME}" administrator
rabbitmqctl -q import_definitions /etc/rabbitmq/definitions.json
rabbitmqctl -q set_permissions -p /mine-platform "${SEED_USERNAME}" '.*' '.*' '.*'
rabbitmqctl -q set_permissions -p /mine-device "${SEED_USERNAME}" '.*' '.*' '.*'
touch /tmp/mine-rabbitmq-bootstrap-complete

wait "${broker_pid}"
