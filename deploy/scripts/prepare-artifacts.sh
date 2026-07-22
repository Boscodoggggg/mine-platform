#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${MINE_ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
DEPLOY_DIR="${ROOT_DIR}/deploy"

source_jar_for() {
  case "$1" in
    mine-server) printf '%s\n' "${ROOT_DIR}/mine-server/target/mine-server-0.1.0-SNAPSHOT.jar" ;;
    mine-gateway) printf '%s\n' "${ROOT_DIR}/mine-gateway/target/mine-gateway-0.1.0-SNAPSHOT.jar" ;;
    mine-telemetry) printf '%s\n' "${ROOT_DIR}/mine-telemetry/target/mine-telemetry-0.1.0-SNAPSHOT.jar" ;;
    *) echo "FAIL: 未知 Java 服务 $1" >&2; return 1 ;;
  esac
}

for service in mine-server mine-gateway mine-telemetry; do
  source_jar="$(source_jar_for "${service}")"
  [[ -s "${source_jar}" ]] || {
    echo "FAIL: 缺少 ${source_jar}，请先执行 ./mvnw -B -ntp package -DskipTests" >&2
    exit 1
  }
done
[[ -s "${ROOT_DIR}/mine-web/dist/index.html" ]] || {
  echo "FAIL: 缺少 mine-web/dist，请先执行 pnpm --dir mine-web build" >&2
  exit 1
}

mkdir -p "${DEPLOY_DIR}/backend" "${DEPLOY_DIR}/frontend"
for service in mine-server mine-gateway mine-telemetry; do
  install -m 0444 "$(source_jar_for "${service}")" "${DEPLOY_DIR}/backend/${service}.jar"
done

frontend_staging="$(mktemp -d "${DEPLOY_DIR}/frontend/.dist.XXXXXX")"
trap 'rm -rf "${frontend_staging}"' EXIT
cp -R "${ROOT_DIR}/mine-web/dist/." "${frontend_staging}/"
rm -rf "${DEPLOY_DIR}/frontend/dist"
mv "${frontend_staging}" "${DEPLOY_DIR}/frontend/dist"
trap - EXIT

echo "PASS: 后端 JAR 与前端 dist 已准备到 deploy 目录"
