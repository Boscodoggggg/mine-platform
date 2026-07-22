#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${ROOT_DIR}/deploy/scripts/prepare-artifacts.sh"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
fixture="${work_dir}/fixture"
mkdir -p "${fixture}"/{deploy/{backend,frontend},mine-server/target,mine-gateway/target,mine-telemetry/target,mine-web/dist}
printf 'server\n' >"${fixture}/mine-server/target/mine-server-0.1.0-SNAPSHOT.jar"
printf 'gateway\n' >"${fixture}/mine-gateway/target/mine-gateway-0.1.0-SNAPSHOT.jar"
printf 'telemetry\n' >"${fixture}/mine-telemetry/target/mine-telemetry-0.1.0-SNAPSHOT.jar"
printf '<html>fixture</html>\n' >"${fixture}/mine-web/dist/index.html"

MINE_ROOT_DIR="${fixture}" "${SCRIPT}"

for service in mine-server mine-gateway mine-telemetry; do
  [[ -s "${fixture}/deploy/backend/${service}.jar" ]] \
    || { echo "FAIL: 未准备 ${service}.jar" >&2; exit 1; }
done
grep -q fixture "${fixture}/deploy/frontend/dist/index.html" \
  || { echo "FAIL: 未准备前端 dist" >&2; exit 1; }

echo "PASS: Bash 3.2 兼容的部署制品准备脚本工作正常"
