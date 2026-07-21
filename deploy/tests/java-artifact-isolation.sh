#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${MINE_COMPOSE_FILE:-${ROOT_DIR}/deploy/compose/compose-standalone.yml}"
ENV_FILE="${MINE_ENV_FILE:-${ROOT_DIR}/deploy/env/standalone.env.example}"

compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
rendered="$("${compose[@]}" config --format json)"

services=(mine-server mine-gateway mine-telemetry)
for service in "${services[@]}"; do
  if jq -e --arg service "${service}" '
      .services[$service].volumes[]?
      | select(.type == "bind" and (.source | endswith(".jar")))
    ' >/dev/null <<<"${rendered}"; then
    echo "FAIL: ${service} 不得直接绑定可被 Maven 替换的 JAR 文件" >&2
    exit 1
  fi

  jq -e --arg service "${service}" '
    any(
      .services[$service].volumes[]?;
      .type == "bind"
      and .target == "/opt/mine/source"
      and .read_only == true
      and (.source | endswith("/target"))
    )
  ' >/dev/null <<<"${rendered}" || {
    echo "FAIL: ${service} 必须只读挂载稳定的 target 目录" >&2
    exit 1
  }

  jq -e --arg service "${service}" '
    (.services[$service].environment.MINE_APP_SOURCE_JAR // "")
      | startswith("/opt/mine/source/") and endswith(".jar")
  ' >/dev/null <<<"${rendered}" || {
    echo "FAIL: ${service} 必须声明容器内源 JAR 路径" >&2
    exit 1
  }

  jq -e --arg service "${service}" '
    (.services[$service].command | join(" "))
      | contains("/opt/mine/run-java-service.sh")
  ' >/dev/null <<<"${rendered}" || {
    echo "FAIL: ${service} 必须通过不可变副本启动脚本运行" >&2
    exit 1
  }
done

echo "PASS: Java 服务均从容器内不可变 JAR 副本启动"
