#!/usr/bin/env bash
set -euo pipefail

source_jar="${MINE_APP_SOURCE_JAR:?必须配置 MINE_APP_SOURCE_JAR}"
runtime_dir="/tmp/mine-runtime"
runtime_jar="${runtime_dir}/app.jar"

if [[ ! -r "${source_jar}" ]]; then
  echo "Java 应用源 JAR 不存在或不可读：${source_jar}" >&2
  exit 1
fi

rm -rf "${runtime_dir}"
mkdir -p "${runtime_dir}"
cp -- "${source_jar}" "${runtime_jar}"

source_size="$(stat -c '%s' "${source_jar}")"
runtime_size="$(stat -c '%s' "${runtime_jar}")"
if (( runtime_size < 1048576 )) || [[ "${source_size}" != "${runtime_size}" ]] \
    || ! cmp -s "${source_jar}" "${runtime_jar}"; then
  echo "Java 应用 JAR 不完整或复制期间发生变化：${source_jar}" >&2
  exit 1
fi

chmod 0444 "${runtime_jar}"
exec java ${JAVA_OPTS:-} -jar "${runtime_jar}"
