#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_DIR="${MINE_RELEASE_DIR:-${ROOT_DIR}/release}"
SCOPE="${MINE_RELEASE_ARTIFACTS_SCOPE:-all}"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

[[ "${SCOPE}" == "all" || "${SCOPE}" == "backend" || "${SCOPE}" == "frontend" ]] \
  || fail "MINE_RELEASE_ARTIFACTS_SCOPE 只支持 all、backend 或 frontend"

if [[ "${SCOPE}" == "all" || "${SCOPE}" == "backend" ]]; then
  for command in jar awk; do
    command -v "${command}" >/dev/null || fail "缺少命令 ${command}"
  done
  for service in mine-server mine-gateway mine-telemetry; do
    artifact="${RELEASE_DIR}/${service}.jar"
    [[ -s "${artifact}" ]] || fail "缺少 release/${service}.jar"
    jar tf "${artifact}" | awk '$0 ~ /^BOOT-INF\// { found = 1 } END { exit !found }' \
      || fail "release/${service}.jar 不是 Spring Boot 可执行 JAR"
  done
fi

if [[ "${SCOPE}" == "all" || "${SCOPE}" == "frontend" ]]; then
  for command in unzip zipinfo grep; do
    command -v "${command}" >/dev/null || fail "缺少命令 ${command}"
  done
  web_artifact="${RELEASE_DIR}/mine-web.zip"
  [[ -s "${web_artifact}" ]] || fail "缺少 release/mine-web.zip"
  unzip -tq "${web_artifact}" >/dev/null || fail "release/mine-web.zip 已损坏"
  entries="$(zipinfo -1 "${web_artifact}")"
  grep -qx 'index.html' <<<"${entries}" || fail "mine-web.zip 根目录缺少 index.html"
  if grep -Eq '(^|/)\.\.?(/|$)|^/|^dist/' <<<"${entries}"; then
    fail "mine-web.zip 包含不安全路径或多余 dist 目录层级"
  fi
fi

echo "PASS: release ${SCOPE} 发行制品结构与完整性正确"
