#!/usr/bin/env bash
set -euo pipefail

source_jar="${MINE_APP_SOURCE_JAR:?必须配置 MINE_APP_SOURCE_JAR}"
runtime_dir="${MINE_RUNTIME_DIR:-/tmp/mine-runtime}"
runtime_jar="${runtime_dir}/app.jar"
runtime_secret_dir="${runtime_dir}/secrets"

materialize_secret() {
  local value_name="$1"
  local file_name="$2"
  local target_name="$3"
  local value="${!value_name-}"

  [[ -n "${value}" ]] || return 0
  printf '%s' "${value}" >"${runtime_secret_dir}/${target_name}"
  chmod 0400 "${runtime_secret_dir}/${target_name}"
  printf -v "${file_name}" '%s' "${runtime_secret_dir}/${target_name}"
  export "${file_name}"
  unset "${value_name}"
}

if [[ ! -r "${source_jar}" ]]; then
  echo "Java 应用源 JAR 不存在或不可读：${source_jar}" >&2
  exit 1
fi

rm -rf "${runtime_dir}"
umask 077
mkdir -p "${runtime_secret_dir}"

materialize_secret MINE_SUPER_ADMIN_PASSWORD MINE_BOOTSTRAP_ADMIN_PASSWORD_FILE mine_super_admin_password
materialize_secret MINE_PERSON_DATA_KEY MINE_PERSON_DATA_KEY_FILE mine_person_data_key
materialize_secret MINE_NOTIFICATION_DATA_KEY MINE_NOTIFICATION_DATA_KEY_FILE mine_notification_data_key
materialize_secret RABBITMQ_ADMIN_USERNAME MINE_RABBITMQ_ADMIN_USERNAME_FILE rabbitmq_admin_username
materialize_secret RABBITMQ_ADMIN_PASSWORD MINE_RABBITMQ_ADMIN_PASSWORD_FILE rabbitmq_admin_password
materialize_secret TIMESCALE_READER_PASSWORD MINE_TELEMETRY_QUERY_PASSWORD_FILE timescale_reader_password
materialize_secret TIMESCALE_REPLAY_PASSWORD MINE_SPATIAL_REPLAY_PASSWORD_FILE timescale_replay_password
materialize_secret RABBITMQ_SERVER_REALTIME_PASSWORD MINE_REALTIME_RABBIT_PASSWORD_FILE rabbitmq_server_realtime_password
materialize_secret RABBITMQ_SERVER_COMMAND_PASSWORD MINE_COMMAND_OUTBOX_RABBIT_PASSWORD_FILE rabbitmq_server_command_password
materialize_secret RABBITMQ_SERVER_ALARM_PASSWORD MINE_ALARM_RABBIT_PASSWORD_FILE rabbitmq_server_alarm_password
materialize_secret RABBITMQ_SERVER_OPERATION_PASSWORD MINE_OPERATION_RUNTIME_ACCOUNT_PASSWORD_FILE rabbitmq_server_operation_password
materialize_secret RABBITMQ_SERVER_OPERATION_OUTBOX_PASSWORD MINE_OPERATION_OUTBOX_RABBIT_PASSWORD_FILE rabbitmq_server_operation_outbox_password
materialize_secret RABBITMQ_GATEWAY_DEVICE_PASSWORD MINE_GATEWAY_DEVICE_RABBIT_PASSWORD_FILE rabbitmq_gateway_device_password
materialize_secret RABBITMQ_GATEWAY_PLATFORM_PASSWORD MINE_GATEWAY_PLATFORM_RABBIT_PASSWORD_FILE rabbitmq_gateway_platform_password
materialize_secret RABBITMQ_TELEMETRY_PASSWORD MINE_TELEMETRY_RABBIT_PASSWORD_FILE rabbitmq_telemetry_password
materialize_secret MINE_CONFIGURATION_DISTRIBUTION_TOKEN MINE_CONFIGURATION_DISTRIBUTION_TOKEN_FILE mine_configuration_distribution_token

cp -- "${source_jar}" "${runtime_jar}"

source_size="$(stat -c '%s' "${source_jar}" 2>/dev/null || stat -f '%z' "${source_jar}")"
runtime_size="$(stat -c '%s' "${runtime_jar}" 2>/dev/null || stat -f '%z' "${runtime_jar}")"
if (( runtime_size < 1048576 )) || [[ "${source_size}" != "${runtime_size}" ]] \
    || ! cmp -s "${source_jar}" "${runtime_jar}"; then
  echo "Java 应用 JAR 不完整或复制期间发生变化：${source_jar}" >&2
  exit 1
fi

chmod 0444 "${runtime_jar}"
exec java ${JAVA_OPTS:-} -jar "${runtime_jar}"
