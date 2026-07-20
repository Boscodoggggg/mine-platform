#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKUP_SCRIPT="${ROOT_DIR}/deploy/scripts/backup-standalone.sh"
RESTORE_SCRIPT="${ROOT_DIR}/deploy/scripts/restore-standalone.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

[[ -x "${BACKUP_SCRIPT}" ]] || fail "缺少可执行备份脚本 ${BACKUP_SCRIPT}"
[[ -x "${RESTORE_SCRIPT}" ]] || fail "缺少可执行恢复脚本 ${RESTORE_SCRIPT}"

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
fixture="${work_dir}/fixture"
fake_bin="${work_dir}/bin"
output_dir="${work_dir}/output"
mkdir -p "${fixture}/deploy/compose" "${fixture}/deploy/env" "${fake_bin}" "${output_dir}"

cat > "${fixture}/deploy/compose/compose-standalone.yml" <<'YAML'
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
printf 'MINE_TEST=true\n' > "${fixture}/deploy/env/standalone.env"
printf '0.1.0-test\n' > "${fixture}/VERSION"

cat > "${fake_bin}/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "${FAKE_DOCKER_LOG}"

if [[ "${1:-}" == "compose" && "$*" == *"config --format json"* ]]; then
  if [[ "${FAKE_COMPOSE_IMAGE_DRIFT:-}" == "true" ]]; then
    postgres_image="example/postgres:drifted"
  else
    postgres_image="example/postgres:18"
  fi
  printf '{"services":{"postgres":{"image":"%s"},"timescale":{"image":"example/timescale:2"},"rabbitmq":{"image":"example/rabbitmq:4"},"rustfs":{"image":"example/rustfs:1"}}}\n' "${postgres_image}"
elif [[ "${1:-}" == "compose" && "$*" == *"ps --status running --services"* ]]; then
  [[ -z "${FAKE_RUNNING_SERVICES:-}" ]] || printf '%s\n' "${FAKE_RUNNING_SERVICES}"
elif [[ "${1:-}" == "image" && "${2:-}" == "inspect" ]]; then
  image="${3:-}"
  case "${image}" in
    example/postgres:18) image_id='sha256:postgres-image' ;;
    example/postgres:drifted) image_id='sha256:postgres-drifted' ;;
    example/timescale:2) image_id='sha256:timescale-image' ;;
    example/rabbitmq:4) image_id='sha256:rabbitmq-image' ;;
    example/rustfs:1) image_id='sha256:rustfs-image' ;;
    *) exit 1 ;;
  esac
  if [[ -n "${FAKE_IMAGE_ID_DRIFT:-}" && "${image}" == *"${FAKE_IMAGE_ID_DRIFT}"* ]]; then
    image_id='sha256:locally-drifted'
  fi
  echo "${image_id}"
elif [[ "${1:-}" == "volume" && "${2:-}" == "inspect" ]]; then
  volume="${3:-}"
  if [[ "${volume}" == mine-source_* ]]; then
    echo 'local'
  elif [[ -n "${FAKE_VOLUME_INSPECT_ERROR:-}" && "${volume}" == "${FAKE_VOLUME_INSPECT_ERROR}" ]]; then
    exit 2
  elif [[ -n "${FAKE_EXISTING_VOLUME:-}" && "${volume}" == "${FAKE_EXISTING_VOLUME}" ]]; then
    echo 'local'
  else
    exit 1
  fi
elif [[ "${1:-}" == "volume" && "${2:-}" == "create" ]]; then
  printf '%s\n' "${*: -1}"
elif [[ "${1:-}" == "run" ]]; then
  output_mount=""
  for argument in "$@"; do
    if [[ "${argument}" == *":/backup" ]]; then
      output_mount="${argument%:/backup}"
    fi
  done
  if [[ -n "${output_mount}" ]]; then
    archive_name="$(printf '%s\n' "$*" | sed -n 's#.*\/backup\/\([a-z_]*\.tar\).*#\1#p')"
    [[ -n "${archive_name}" ]] || exit 1
    printf 'fixture volume %s\n' "${archive_name}" > "${output_mount}/${archive_name}"
  elif [[ "$*" != *":/target"* || "$*" != *":/restore/source.tar:ro"* ]]; then
    echo "invalid restore mount: $*" >&2
    exit 1
  fi
else
  echo "unexpected docker command: $*" >&2
  exit 1
fi
FAKE_DOCKER
chmod +x "${fake_bin}/docker"

cat > "${fake_bin}/tar" <<'FAKE_TAR'
#!/usr/bin/env bash
set -euo pipefail
echo "$*" >> "${FAKE_TAR_LOG}"
if [[ "${1:-}" == "-tf" && -n "${FAKE_TAR_LIST:-}" ]]; then
  printf '%s\n' "${FAKE_TAR_LIST}"
else
  /usr/bin/tar "$@"
fi
FAKE_TAR
chmod +x "${fake_bin}/tar"

export PATH="${fake_bin}:${PATH}"
export FAKE_DOCKER_LOG="${work_dir}/docker.log"
export FAKE_TAR_LOG="${work_dir}/tar.log"
export MINE_ROOT_DIR="${fixture}"
export MINE_BACKUP_ID="20260720T220000Z-a1b2c3d4"
export MINE_BACKUP_CREATED_AT="2026-07-20T22:00:00Z"

"${BACKUP_SCRIPT}" \
  --env-file "${fixture}/deploy/env/standalone.env" \
  --project-name mine-source \
  --release-version 0.1.0-test \
  --output-dir "${output_dir}" \
  --confirm-secure-destination >/dev/null

valid_archive="${output_dir}/mine-standalone-backup-0.1.0-test-${MINE_BACKUP_ID}.tar"
[[ -s "${valid_archive}" && -s "${valid_archive}.sha256" ]] || fail "未生成有效恢复 fixture"

hash_path() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

write_external_checksum() {
  local archive="$1"
  printf '%s  %s\n' "$(hash_path "${archive}")" "$(basename "${archive}")" > "${archive}.sha256"
}

rebuild_internal_checksum() {
  local backup_root="$1"
  (
    cd "${backup_root}"
    : > manifests/checksums.sha256
    for path in manifest.json volumes/postgres_data.tar volumes/timescale_data.tar volumes/rabbitmq_data.tar volumes/rustfs_data.tar; do
      [[ -e "${path}" ]] || continue
      printf '%s  %s\n' "$(hash_path "${path}")" "${path}"
    done > manifests/checksums.sha256
  )
}

make_variant() {
  local name="$1"
  local mutation="$2"
  local variant_dir="${work_dir}/variant-${name}"
  local archive="${output_dir}/${name}.tar"
  mkdir -p "${variant_dir}"
  /usr/bin/tar -xf "${valid_archive}" -C "${variant_dir}"
  local backup_root="${variant_dir}/mine-standalone-backup-${MINE_BACKUP_ID}"

  case "${mutation}" in
    corrupt-internal)
      printf 'corrupted\n' >> "${backup_root}/volumes/timescale_data.tar"
      ;;
    schema)
      jq '.schemaVersion = 2' "${backup_root}/manifest.json" > "${backup_root}/manifest.json.tmp"
      mv "${backup_root}/manifest.json.tmp" "${backup_root}/manifest.json"
      rebuild_internal_checksum "${backup_root}"
      ;;
    volume-set)
      jq '.volumes = .volumes[0:3]' "${backup_root}/manifest.json" > "${backup_root}/manifest.json.tmp"
      mv "${backup_root}/manifest.json.tmp" "${backup_root}/manifest.json"
      rebuild_internal_checksum "${backup_root}"
      ;;
    *) fail "未知 fixture 变更 ${mutation}" ;;
  esac

  /usr/bin/tar -cf "${archive}" -C "${variant_dir}" "mine-standalone-backup-${MINE_BACKUP_ID}"
  write_external_checksum "${archive}"
  printf '%s\n' "${archive}"
}

corrupt_internal_archive="$(make_variant corrupt-internal corrupt-internal)"
schema_archive="$(make_variant invalid-schema schema)"
volume_set_archive="$(make_variant invalid-volume-set volume-set)"
external_corrupt_archive="${output_dir}/external-corrupt.tar"
cp "${valid_archive}" "${external_corrupt_archive}"
printf '%064d  %s\n' 0 "$(basename "${external_corrupt_archive}")" > "${external_corrupt_archive}.sha256"

restore_command=(
  "${RESTORE_SCRIPT}"
  --archive "${valid_archive}"
  --env-file "${fixture}/deploy/env/standalone.env"
  --project-name mine-restore
  --expected-release-version 0.1.0-test
  --confirm-backup-id "${MINE_BACKUP_ID}"
)

tests=0
run_expect_failure() {
  local message="$1"
  shift
  if "$@" >"${work_dir}/stdout" 2>"${work_dir}/stderr"; then
    fail "${message}"
  fi
  tests=$((tests + 1))
}

assert_no_volume_create() {
  ! grep -q '^volume create ' "${FAKE_DOCKER_LOG}" || fail "$1"
}

: > "${FAKE_DOCKER_LOG}"
run_expect_failure "未确认 backupId 时恢复仍然成功" \
  "${RESTORE_SCRIPT}" --archive "${valid_archive}" \
  --env-file "${fixture}/deploy/env/standalone.env" --project-name mine-restore \
  --expected-release-version 0.1.0-test
[[ ! -s "${FAKE_DOCKER_LOG}" ]] || fail "未确认 backupId 时不应调用 Docker"

: > "${FAKE_DOCKER_LOG}"
run_expect_failure "错误 backupId 仍被接受" \
  "${RESTORE_SCRIPT}" --archive "${valid_archive}" \
  --env-file "${fixture}/deploy/env/standalone.env" --project-name mine-restore \
  --expected-release-version 0.1.0-test --confirm-backup-id 20260720T220000Z-deadbeef
assert_no_volume_create "错误 backupId 时不应创建卷"

for archive in "${external_corrupt_archive}" "${corrupt_internal_archive}" "${schema_archive}" "${volume_set_archive}"; do
  : > "${FAKE_DOCKER_LOG}"
  run_expect_failure "损坏或非法备份仍然恢复成功：$(basename "${archive}")" \
    "${RESTORE_SCRIPT}" --archive "${archive}" \
    --env-file "${fixture}/deploy/env/standalone.env" --project-name mine-restore \
    --expected-release-version 0.1.0-test --confirm-backup-id "${MINE_BACKUP_ID}"
  assert_no_volume_create "损坏或非法备份不应创建卷"
done

: > "${FAKE_DOCKER_LOG}"
run_expect_failure "发行版本不一致时恢复仍然成功" \
  "${RESTORE_SCRIPT}" --archive "${valid_archive}" \
  --env-file "${fixture}/deploy/env/standalone.env" --project-name mine-restore \
  --expected-release-version 0.2.0-test --confirm-backup-id "${MINE_BACKUP_ID}"
assert_no_volume_create "发行版本不一致时不应创建卷"

for malicious_list in "/absolute-root/manifest.json" \
  "mine-standalone-backup-${MINE_BACKUP_ID}/../escape" \
  $'first-root/manifest.json\nsecond-root/manifest.json'; do
  : > "${FAKE_DOCKER_LOG}"
  : > "${FAKE_TAR_LOG}"
  FAKE_TAR_LIST="${malicious_list}" run_expect_failure "恶意 tar 路径仍被接受" "${restore_command[@]}"
  assert_no_volume_create "恶意 tar 路径不应创建卷"
  ! grep -q '^-xf ' "${FAKE_TAR_LOG}" || fail "恶意 tar 路径不应被解包"
done

: > "${FAKE_DOCKER_LOG}"
FAKE_RUNNING_SERVICES=mine-server run_expect_failure "目标服务运行时恢复仍然成功" "${restore_command[@]}"
assert_no_volume_create "目标服务运行时不应创建卷"

: > "${FAKE_DOCKER_LOG}"
FAKE_COMPOSE_IMAGE_DRIFT=true run_expect_failure "目标镜像引用漂移时恢复仍然成功" "${restore_command[@]}"
assert_no_volume_create "目标镜像引用漂移时不应创建卷"

: > "${FAKE_DOCKER_LOG}"
FAKE_IMAGE_ID_DRIFT=timescale run_expect_failure "目标镜像 ID 漂移时恢复仍然成功" "${restore_command[@]}"
assert_no_volume_create "目标镜像 ID 漂移时不应创建卷"

: > "${FAKE_DOCKER_LOG}"
FAKE_EXISTING_VOLUME=mine-restore_rabbitmq_data run_expect_failure "目标卷已存在时恢复仍然成功" "${restore_command[@]}"
assert_no_volume_create "任一目标卷已存在时不应创建任何卷"

: > "${FAKE_DOCKER_LOG}"
FAKE_VOLUME_INSPECT_ERROR=mine-restore_postgres_data run_expect_failure "卷检查异常被当作不存在" "${restore_command[@]}"
assert_no_volume_create "卷检查异常时不应创建卷"

: > "${FAKE_DOCKER_LOG}"
"${restore_command[@]}" > "${work_dir}/success-output"
tests=$((tests + 1))

expected_volumes=(postgres_data timescale_data rabbitmq_data rustfs_data)
[[ "$(grep -c '^volume create --driver local mine-restore_' "${FAKE_DOCKER_LOG}")" == "4" ]] \
  || fail "恢复没有创建四个 local 目标卷"
[[ "$(grep -c '^run .*--network none ' "${FAKE_DOCKER_LOG}")" == "4" ]] \
  || fail "恢复容器没有全部关闭网络"
for logical_name in "${expected_volumes[@]}"; do
  grep -Fq -- "mine-restore_${logical_name}:/target" "${FAKE_DOCKER_LOG}" \
    || fail "${logical_name} 未挂载到恢复目标"
done
[[ "$(grep -c ':/restore/source.tar:ro' "${FAKE_DOCKER_LOG}")" == "4" ]] \
  || fail "卷归档没有全部以只读方式挂载"
! grep -Eq 'compose .* (up|down)( |$)|^volume rm ' "${FAKE_DOCKER_LOG}" \
  || fail "恢复脚本不得启动、停止服务或删除卷"
grep -q '尚未启动服务' "${work_dir}/success-output" || fail "恢复成功后缺少人工启动提示"

echo "PASS: 单机恢复先验证、拒绝覆盖与不自动启动契约通过（${tests} 项）"
