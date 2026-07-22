#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_SCRIPT="${ROOT_DIR}/deploy/scripts/build-offline-bundle.sh"
INSTALL_SCRIPT="${ROOT_DIR}/deploy/scripts/install-offline.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -x "${BUILD_SCRIPT}" ]] || fail "缺少可执行打包脚本"
[[ -x "${INSTALL_SCRIPT}" ]] || fail "缺少可执行安装脚本"
grep -Eq 'timescale_data:/var/lib/postgresql[[:space:]]*$' \
  "${ROOT_DIR}/deploy/docker-compose.yml" \
  || fail "PostgreSQL 18 TimescaleDB 数据卷必须挂载到 /var/lib/postgresql"
if [[ -s "${ROOT_DIR}/mine-telemetry/target/mine-telemetry-0.1.0-SNAPSHOT.jar" ]]; then
  jar tf "${ROOT_DIR}/mine-telemetry/target/mine-telemetry-0.1.0-SNAPSHOT.jar" \
    | grep -Eq '^BOOT-INF/lib/snakeyaml-[^/]+\.jar$' \
    || fail "mine-telemetry 生产 JAR 缺少 SnakeYAML"
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
fixture="${work_dir}/fixture"
fake_bin="${work_dir}/bin"
mkdir -p "${fixture}"/{deploy/{config/gateway,scripts/lib,tests,certs},mine-server/target,mine-gateway/target,mine-telemetry/target,mine-web/dist,target/sbom} "${fake_bin}"

printf 'license\n' > "${fixture}/LICENSE"
printf 'notice\n' > "${fixture}/NOTICE"
printf 'third party\n' > "${fixture}/THIRD-PARTY-NOTICES.md"
printf 'services: {}\n' > "${fixture}/deploy/docker-compose.yml"
printf 'MINE_TEST=true\n' > "${fixture}/deploy/.env.example"
printf 'SITE_SECRET=must-not-be-packed\n' > "${fixture}/deploy/.env"
printf 'certificate instructions\n' > "${fixture}/deploy/certs/README.md"
printf 'private key\n' > "${fixture}/deploy/certs/tls.key"
printf '{"version":1,"devices":[]}\n' > "${fixture}/deploy/config/gateway/device-identities.json"
printf '#!/usr/bin/env bash\necho "preflight profile=${MINE_PREFLIGHT_PROFILE:-}" >> "${FAKE_DOCKER_LOG}"\n' > "${fixture}/deploy/scripts/preflight.sh"
printf '#!/usr/bin/env bash\necho "health project=${MINE_COMPOSE_PROJECT_NAME:-}" >> "${FAKE_DOCKER_LOG}"\n' > "${fixture}/deploy/scripts/health-check.sh"
cp "${BUILD_SCRIPT}" "${INSTALL_SCRIPT}" "${fixture}/deploy/scripts/"
cp "${ROOT_DIR}/deploy/scripts/backup-standalone.sh" \
  "${ROOT_DIR}/deploy/scripts/restore-standalone.sh" \
  "${fixture}/deploy/scripts/"
cp "${ROOT_DIR}/deploy/scripts/lib/standalone-recovery-common.sh" \
  "${fixture}/deploy/scripts/lib/"
cp "${ROOT_DIR}/deploy/tests/standalone-backup.sh" \
  "${ROOT_DIR}/deploy/tests/standalone-restore.sh" \
  "${fixture}/deploy/tests/"
printf '#!/usr/bin/env bash\n' > "${fixture}/deploy/tests/standalone-smoke.sh"
printf '#!/usr/bin/env bash\necho "observability project=${MINE_COMPOSE_PROJECT_NAME:-}" >> "${FAKE_DOCKER_LOG}"\n' > "${fixture}/deploy/tests/observability-smoke.sh"
chmod +x "${fixture}/deploy/scripts/preflight.sh" "${fixture}/deploy/scripts/health-check.sh" \
  "${fixture}/deploy/scripts/backup-standalone.sh" \
  "${fixture}/deploy/scripts/restore-standalone.sh" \
  "${fixture}/deploy/scripts/lib/standalone-recovery-common.sh" \
  "${fixture}/deploy/tests/standalone-smoke.sh" \
  "${fixture}/deploy/tests/observability-smoke.sh" \
  "${fixture}/deploy/tests/standalone-backup.sh" \
  "${fixture}/deploy/tests/standalone-restore.sh"
printf 'server\n' > "${fixture}/mine-server/target/mine-server-0.1.0-SNAPSHOT.jar"
printf 'gateway\n' > "${fixture}/mine-gateway/target/mine-gateway-0.1.0-SNAPSHOT.jar"
printf 'telemetry\n' > "${fixture}/mine-telemetry/target/mine-telemetry-0.1.0-SNAPSHOT.jar"
printf '<html></html>\n' > "${fixture}/mine-web/dist/index.html"
printf '{"bomFormat":"CycloneDX","specVersion":"1.6"}\n' > "${fixture}/target/sbom/backend.json"
printf '{"bomFormat":"CycloneDX","specVersion":"1.6"}\n' > "${fixture}/target/sbom/frontend.json"

cat > "${fake_bin}/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "${FAKE_DOCKER_LOG}"
if [[ "${1:-}" == "compose" && "$*" == *"config --images"* ]]; then
  env_file=""
  while [[ $# -gt 0 ]]; do
    if [[ "$1" == "--env-file" ]]; then
      env_file="$2"
      break
    fi
    shift
  done
  if [[ -n "${env_file}" ]] && grep -q 'example/unlocked:1' "${env_file}"; then
    echo 'example/unlocked:1'
  else
    echo 'example/platform:1'
  fi
elif [[ "${1:-}" == "image" && "${2:-}" == "inspect" ]]; then
  echo 'sha256:fixture-image'
elif [[ "${1:-}" == "save" ]]; then
  while [[ $# -gt 0 ]]; do
    if [[ "$1" == "--output" ]]; then
      printf 'fixture image\n' > "$2"
      exit 0
    fi
    shift
  done
elif [[ "${1:-}" == "load" ]]; then
  echo 'Loaded image: example/platform:1'
fi
FAKE_DOCKER
chmod +x "${fake_bin}/docker"
export PATH="${fake_bin}:${PATH}"
export FAKE_DOCKER_LOG="${work_dir}/docker.log"

MINE_ROOT_DIR="${fixture}" "${BUILD_SCRIPT}" --version 0.1.0-test --output-dir "${work_dir}/out"
archive="${work_dir}/out/mine-platform-0.1.0-test.tar"
[[ -s "${archive}" ]] || fail "未生成离线归档"
mkdir -p "${work_dir}/extract"
tar -xf "${archive}" -C "${work_dir}/extract"
bundle="${work_dir}/extract/mine-platform-0.1.0-test"
[[ -s "${bundle}/images/images.tar" ]] || fail "离线包缺少镜像归档"
[[ -s "${bundle}/manifests/images.lock" ]] || fail "离线包缺少镜像锁"
[[ -s "${bundle}/sbom/backend.json" && -s "${bundle}/sbom/frontend.json" ]] || fail "离线包缺少 SBOM"
[[ -s "${bundle}/deploy/docker-compose.yml" ]] || fail "离线包缺少 deploy/docker-compose.yml"
[[ -s "${bundle}/deploy/.env.example" ]] || fail "离线包缺少 deploy/.env.example"
[[ -s "${bundle}/deploy/backend/mine-server.jar" ]] || fail "离线包缺少 server JAR"
[[ -s "${bundle}/deploy/backend/mine-gateway.jar" ]] || fail "离线包缺少 gateway JAR"
[[ -s "${bundle}/deploy/backend/mine-telemetry.jar" ]] || fail "离线包缺少 telemetry JAR"
[[ -s "${bundle}/deploy/frontend/dist/index.html" ]] || fail "离线包缺少前端 dist"
[[ ! -e "${bundle}/deploy/.env" ]] || fail "离线包只能包含环境变量示例，不能包含现场 .env"
[[ ! -e "${bundle}/deploy/certs/tls.key" ]] || fail "离线包不得包含现场 TLS 私钥"
recovery_assets=(
  deploy/scripts/backup-standalone.sh
  deploy/scripts/restore-standalone.sh
  deploy/scripts/lib/standalone-recovery-common.sh
  deploy/tests/standalone-backup.sh
  deploy/tests/standalone-restore.sh
)
for relative in "${recovery_assets[@]}"; do
  [[ -x "${bundle}/${relative}" ]] || fail "离线包缺少可执行恢复资产 ${relative}"
  awk -v path="./${relative}" '$2 == path {found = 1} END {exit !found}' \
    "${bundle}/manifests/checksums.sha256" \
    || fail "离线包校验和未覆盖恢复资产 ${relative}"
done

: > "${FAKE_DOCKER_LOG}"
if "${bundle}/deploy/scripts/install-offline.sh" --bundle-dir "${bundle}" >/dev/null 2>&1; then
  fail "缺少现场环境文件时安装脚本仍然成功"
fi
[[ ! -s "${FAKE_DOCKER_LOG}" ]] || fail "缺少现场环境文件时不应调用 Docker 或执行预检"

: > "${FAKE_DOCKER_LOG}"
"${bundle}/deploy/scripts/install-offline.sh" --bundle-dir "${bundle}" --load-only
grep -q '^load ' "${FAKE_DOCKER_LOG}" || fail "安装脚本没有导入镜像"
grep -q '^image inspect example/platform:1 ' "${FAKE_DOCKER_LOG}" || fail "安装脚本没有复核镜像 ID"

site_env="${work_dir}/site.env"
printf 'MINE_TEST=true\n' > "${site_env}"
: > "${FAKE_DOCKER_LOG}"
"${bundle}/deploy/scripts/install-offline.sh" --bundle-dir "${bundle}" --env-file "${site_env}" \
  --project-name mine-standalone --profile observability
grep -q '^preflight profile=observability$' "${FAKE_DOCKER_LOG}" || fail "安装脚本没有向预检传递 profile"
grep -q '^compose -p mine-standalone .*--profile observability config --images$' "${FAKE_DOCKER_LOG}" \
  || fail "安装脚本没有按现场环境解析 profile 镜像"
grep -q '^compose -p mine-standalone .*--profile observability up -d --pull never$' "${FAKE_DOCKER_LOG}" \
  || fail "安装脚本没有使用项目名和 --pull never 启动"
grep -q '^health project=mine-standalone$' "${FAKE_DOCKER_LOG}" || fail "健康检查没有收到项目名"
grep -q '^observability project=mine-standalone$' "${FAKE_DOCKER_LOG}" || fail "可观测性验收没有收到项目名"

: > "${FAKE_DOCKER_LOG}"
if "${bundle}/deploy/scripts/install-offline.sh" --bundle-dir "${bundle}" --env-file "${site_env}" \
  --project-name mine-review --profile observability >/dev/null 2>&1; then
  fail "observability 使用非标准项目名时安装脚本仍然成功"
fi
[[ ! -s "${FAKE_DOCKER_LOG}" ]] || fail "项目名不兼容时不应执行预检或调用 Docker"

printf 'MINE_JAVA_IMAGE=example/unlocked:1\n' > "${site_env}"
: > "${FAKE_DOCKER_LOG}"
if "${bundle}/deploy/scripts/install-offline.sh" --bundle-dir "${bundle}" --env-file "${site_env}" >/dev/null 2>&1; then
  fail "现场环境引用未锁定镜像时安装脚本仍然成功"
fi
! grep -q '^load ' "${FAKE_DOCKER_LOG}" || fail "镜像引用不匹配时不应导入镜像"

printf 'corrupted\n' >> "${bundle}/deploy/frontend/dist/index.html"
: > "${FAKE_DOCKER_LOG}"
if "${bundle}/deploy/scripts/install-offline.sh" --bundle-dir "${bundle}" --load-only >/dev/null 2>&1; then
  fail "文件损坏后安装脚本仍然成功"
fi
[[ ! -s "${FAKE_DOCKER_LOG}" ]] || fail "校验和失败后不应调用 Docker"

echo "PASS: 离线 deploy 目录、现场敏感文件排除、校验和、镜像导入与镜像 ID 复核均正确"
