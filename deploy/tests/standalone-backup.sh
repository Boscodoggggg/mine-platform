#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_SCRIPT="${ROOT_DIR}/deploy/scripts/backup-standalone.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -x "${BACKUP_SCRIPT}" ]] || fail "缺少可执行备份脚本 ${BACKUP_SCRIPT}"

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
fixture="${work_dir}/fixture"
fake_bin="${work_dir}/bin"
output_dir="${work_dir}/output"
mkdir -p "${fixture}/deploy" "${fake_bin}" "${output_dir}"

cat > "${fixture}/deploy/docker-compose.yml" <<'YAML'
services:
  postgres:
    image: example/postgres:18
  timescale:
    image: example/timescale:2
  rabbitmq:
    image: example/rabbitmq:4
  rustfs:
    image: example/rustfs:1
volumes:
  postgres_data: {}
  timescale_data: {}
  rabbitmq_data: {}
  rustfs_data: {}
YAML
printf 'MINE_TEST=true\n' > "${fixture}/deploy/.env"
printf '0.1.0-test\n' > "${fixture}/VERSION"

cat > "${fake_bin}/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "${FAKE_DOCKER_LOG}"

if [[ "${1:-}" == "compose" && "$*" == *"config --format json"* ]]; then
  cat <<'JSON'
{"services":{"postgres":{"image":"example/postgres:18"},"timescale":{"image":"example/timescale:2"},"rabbitmq":{"image":"example/rabbitmq:4"},"rustfs":{"image":"example/rustfs:1"}}}
JSON
elif [[ "${1:-}" == "compose" && "$*" == *"ps --status running --services"* ]]; then
  [[ -z "${FAKE_RUNNING_SERVICES:-}" ]] || printf '%s\n' "${FAKE_RUNNING_SERVICES}"
elif [[ "${1:-}" == "image" && "${2:-}" == "inspect" ]]; then
  case "${3:-}" in
    example/postgres:18) echo 'sha256:postgres-image' ;;
    example/timescale:2) echo 'sha256:timescale-image' ;;
    example/rabbitmq:4) echo 'sha256:rabbitmq-image' ;;
    example/rustfs:1) echo 'sha256:rustfs-image' ;;
    *) exit 1 ;;
  esac
elif [[ "${1:-}" == "volume" && "${2:-}" == "inspect" ]]; then
  volume="${3:-}"
  [[ "${volume}" != "${FAKE_MISSING_VOLUME:-}" ]] || exit 1
  if [[ -n "${FAKE_NON_LOCAL_VOLUME:-}" && "${volume}" == "${FAKE_NON_LOCAL_VOLUME}" ]]; then
    echo 'nfs'
  else
    echo 'local'
  fi
elif [[ "${1:-}" == "run" ]]; then
  output_mount=""
  for argument in "$@"; do
    if [[ "${argument}" == *":/backup" ]]; then
      output_mount="${argument%:/backup}"
    fi
  done
  [[ -n "${output_mount}" ]] || exit 1
  archive_name="$(printf '%s\n' "$*" | sed -n 's#.*\/backup\/\([a-z_]*\.tar\).*#\1#p')"
  [[ -n "${archive_name}" ]] || exit 1
  printf 'fixture volume %s\n' "${archive_name}" > "${output_mount}/${archive_name}"
else
  echo "unexpected docker command: $*" >&2
  exit 1
fi
FAKE_DOCKER
chmod +x "${fake_bin}/docker"

export PATH="${fake_bin}:${PATH}"
export FAKE_DOCKER_LOG="${work_dir}/docker.log"
export MINE_BACKUP_ID="20260720T220000Z-a1b2c3d4"
export MINE_BACKUP_CREATED_AT="2026-07-20T22:00:00Z"

tests=0

backup_command=(
  "${BACKUP_SCRIPT}"
  --env-file "${fixture}/deploy/.env"
  --project-name mine-review
  --release-version 0.1.0-test
  --output-dir "${output_dir}"
)

run_expect_failure() {
  local message="$1"
  shift
  if "$@" >"${work_dir}/stdout" 2>"${work_dir}/stderr"; then
    fail "${message}"
  fi
  tests=$((tests + 1))
}

: > "${FAKE_DOCKER_LOG}"
run_expect_failure "未确认安全目的地时备份仍然成功" "${backup_command[@]}"
[[ ! -s "${FAKE_DOCKER_LOG}" ]] || fail "未确认安全目的地时不应调用 Docker"

: > "${FAKE_DOCKER_LOG}"
run_expect_failure "非法项目名仍被接受" \
  "${BACKUP_SCRIPT}" --env-file "${fixture}/deploy/.env" \
  --project-name 'Mine Invalid' --release-version 0.1.0-test \
  --output-dir "${output_dir}" --confirm-secure-destination
[[ ! -s "${FAKE_DOCKER_LOG}" ]] || fail "非法项目名不应调用 Docker"

: > "${FAKE_DOCKER_LOG}"
FAKE_RUNNING_SERVICES=mine-server run_expect_failure \
  "存在运行服务时备份仍然成功" "${backup_command[@]}" --confirm-secure-destination
! grep -q '^run ' "${FAKE_DOCKER_LOG}" || fail "存在运行服务时不应启动归档容器"

: > "${FAKE_DOCKER_LOG}"
FAKE_MISSING_VOLUME=mine-review_timescale_data run_expect_failure \
  "缺少事实卷时备份仍然成功" "${backup_command[@]}" --confirm-secure-destination
! grep -q '^run ' "${FAKE_DOCKER_LOG}" || fail "缺少事实卷时不应启动归档容器"

: > "${FAKE_DOCKER_LOG}"
FAKE_NON_LOCAL_VOLUME=mine-review_rustfs_data run_expect_failure \
  "非 local 事实卷仍被归档" "${backup_command[@]}" --confirm-secure-destination
! grep -q '^run ' "${FAKE_DOCKER_LOG}" || fail "非 local 事实卷不应启动归档容器"

: > "${FAKE_DOCKER_LOG}"
"${backup_command[@]}" --confirm-secure-destination
tests=$((tests + 1))

archive="${output_dir}/mine-standalone-backup-0.1.0-test-${MINE_BACKUP_ID}.tar"
checksum="${archive}.sha256"
[[ -s "${archive}" && -s "${checksum}" ]] || fail "备份归档或外部校验和不存在"

mkdir -p "${work_dir}/extract"
tar -xf "${archive}" -C "${work_dir}/extract"
backup_root="${work_dir}/extract/mine-standalone-backup-${MINE_BACKUP_ID}"
manifest="${backup_root}/manifest.json"
internal_checksums="${backup_root}/manifests/checksums.sha256"
[[ -s "${manifest}" && -s "${internal_checksums}" ]] || fail "备份缺少 manifest 或内部校验和"

expected_volumes=(postgres_data timescale_data rabbitmq_data rustfs_data)
for logical_name in "${expected_volumes[@]}"; do
  [[ -s "${backup_root}/volumes/${logical_name}.tar" ]] || fail "备份缺少 ${logical_name} 归档"
  grep -Fq -- "mine-review_${logical_name}:/source:ro" "${FAKE_DOCKER_LOG}" \
    || fail "${logical_name} 没有只读挂载"
done
[[ "$(grep -c '^run ' "${FAKE_DOCKER_LOG}")" == "4" ]] || fail "备份归档容器数量不是四个"
[[ "$(grep -c '^run .*--network none ' "${FAKE_DOCKER_LOG}")" == "4" ]] \
  || fail "备份归档容器没有全部关闭网络"

jq -e '
  .schemaVersion == 1
  and .backupId == "20260720T220000Z-a1b2c3d4"
  and .createdAt == "2026-07-20T22:00:00Z"
  and .releaseVersion == "0.1.0-test"
  and .sourceProject == "mine-review"
  and (.composeSha256 | test("^[a-f0-9]{64}$"))
  and .services.postgres == {"image":"example/postgres:18","imageId":"sha256:postgres-image"}
  and .services.timescale == {"image":"example/timescale:2","imageId":"sha256:timescale-image"}
  and .services.rabbitmq == {"image":"example/rabbitmq:4","imageId":"sha256:rabbitmq-image"}
  and .services.rustfs == {"image":"example/rustfs:1","imageId":"sha256:rustfs-image"}
  and ([.volumes[].logicalName] == ["postgres_data","timescale_data","rabbitmq_data","rustfs_data"])
  and ([.volumes[].driver] | all(. == "local"))
' "${manifest}" >/dev/null || fail "备份 manifest 内容不符合契约"

if command -v sha256sum >/dev/null; then
  (cd "${backup_root}" && sha256sum --check manifests/checksums.sha256 >/dev/null)
  (cd "${output_dir}" && sha256sum --check "$(basename "${checksum}")" >/dev/null)
else
  (cd "${backup_root}" && shasum -a 256 --check manifests/checksums.sha256 >/dev/null)
  (cd "${output_dir}" && shasum -a 256 --check "$(basename "${checksum}")" >/dev/null)
fi

if tar -tf "${archive}" | grep -Eq '(^|/)(deploy/\.env|deploy/certs|redis_data|prometheus_data|scene_upload_workspace|gateway_configuration_cache)(/|$)'; then
  fail "备份包含环境、证书或非事实卷"
fi

: > "${FAKE_DOCKER_LOG}"
run_expect_failure "已存在同名备份时仍覆盖成功" \
  "${backup_command[@]}" --confirm-secure-destination
! grep -q '^run ' "${FAKE_DOCKER_LOG}" || fail "输出冲突时不应重新归档卷"

echo "PASS: 单机冷备份安全契约通过（${tests} 项）"
