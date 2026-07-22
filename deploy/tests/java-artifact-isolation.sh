#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${MINE_COMPOSE_FILE:-${ROOT_DIR}/deploy/docker-compose.yml}"
ENV_FILE="${MINE_ENV_FILE:-${ROOT_DIR}/deploy/.env.example}"

compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
rendered="$("${compose[@]}" config --format json)"

services=(mine-server mine-gateway mine-telemetry)
for service in "${services[@]}"; do
  jq -e --arg service "${service}" '
    any(
      .services[$service].volumes[]?;
      .type == "bind"
      and .target == "/opt/mine/source/app.jar"
      and .read_only == true
      and (.source | endswith(".jar"))
    )
  ' >/dev/null <<<"${rendered}" || {
    echo "FAIL: ${service} 必须只读挂载独立发行 JAR" >&2
    exit 1
  }

  jq -e --arg service "${service}" '
    .services[$service].environment.MINE_APP_SOURCE_JAR == "/opt/mine/source/app.jar"
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

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
mkdir -p "${work_dir}/bin"
dd if=/dev/zero of="${work_dir}/source.jar" bs=1048576 count=2 >/dev/null 2>&1

cat >"${work_dir}/bin/java" <<'FAKE_JAVA'
#!/usr/bin/env bash
set -euo pipefail
[[ -z "${MINE_PERSON_DATA_KEY+x}" ]]
[[ -f "${MINE_PERSON_DATA_KEY_FILE}" ]]
[[ "$(<"${MINE_PERSON_DATA_KEY_FILE}")" == "test-person-key" ]]
mode="$(stat -c '%a' "${MINE_PERSON_DATA_KEY_FILE}" 2>/dev/null || stat -f '%Lp' "${MINE_PERSON_DATA_KEY_FILE}")"
[[ "${mode}" == "400" ]]
[[ "$*" == "-jar "*"/app.jar" ]]
FAKE_JAVA
chmod +x "${work_dir}/bin/java"

PATH="${work_dir}/bin:${PATH}" \
MINE_RUNTIME_DIR="${work_dir}/runtime" \
MINE_APP_SOURCE_JAR="${work_dir}/source.jar" \
MINE_PERSON_DATA_KEY="test-person-key" \
  "${ROOT_DIR}/deploy/scripts/run-java-service.sh"

echo "PASS: Java 服务均挂载独立 JAR，并从清除明文环境变量后的不可变副本启动"
