#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
START_SCRIPT="${ROOT_DIR}/deploy/scripts/start.sh"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
fixture="${work_dir}/fixture"
fake_bin="${work_dir}/bin"
mkdir -p "${fixture}/deploy/scripts" "${fake_bin}"
cp "${START_SCRIPT}" "${fixture}/deploy/scripts/start.sh"
printf 'services: {}\n' >"${fixture}/deploy/docker-compose.yml"
printf 'MINE_DEPLOY_PROFILE=observability\n' >"${fixture}/deploy/.env"

cat >"${fixture}/deploy/scripts/preflight.sh" <<'FAKE_PREFLIGHT'
#!/usr/bin/env bash
printf 'preflight profile=%s\n' "${MINE_PREFLIGHT_PROFILE:-}" >>"${COMMAND_LOG}"
FAKE_PREFLIGHT
cat >"${fixture}/deploy/scripts/health-check.sh" <<'FAKE_HEALTH'
#!/usr/bin/env bash
printf 'health\n' >>"${COMMAND_LOG}"
FAKE_HEALTH
cat >"${fake_bin}/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >>"${COMMAND_LOG}"
FAKE_DOCKER
chmod +x "${fixture}/deploy/scripts/"*.sh "${fake_bin}/docker"

export PATH="${fake_bin}:${PATH}"
export COMMAND_LOG="${work_dir}/commands.log"

: >"${COMMAND_LOG}"
MINE_ROOT_DIR="${fixture}" MINE_DEPLOY_PROFILE= "${fixture}/deploy/scripts/start.sh"
grep -q '^preflight profile=$' "${COMMAND_LOG}" || fail "调用前显式空 profile 未覆盖 .env"
grep -q '^docker compose .* up -d$' "${COMMAND_LOG}" || fail "核心服务启动命令不正确"
! grep -q -- '--profile observability' "${COMMAND_LOG}" || fail "显式空 profile 仍启动了可观测栈"

: >"${COMMAND_LOG}"
env -u MINE_DEPLOY_PROFILE MINE_ROOT_DIR="${fixture}" "${fixture}/deploy/scripts/start.sh"
grep -q '^preflight profile=observability$' "${COMMAND_LOG}" || fail "未读取 .env 中的 profile"
grep -q -- '--profile observability up -d$' "${COMMAND_LOG}" || fail "可观测栈启动命令不正确"

echo "PASS: 启动命令支持 .env profile，并允许调用前环境显式覆盖"
