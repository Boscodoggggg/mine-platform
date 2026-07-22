#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${MINE_ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON_LIB="${SCRIPT_DIR}/lib/standalone-recovery-common.sh"
[[ -f "${COMMON_LIB}" ]] || { echo "FAIL: 缺少公共恢复库 ${COMMON_LIB}" >&2; exit 1; }
# shellcheck source=deploy/scripts/lib/standalone-recovery-common.sh
source "${COMMON_LIB}"

ARCHIVE=""
ENV_FILE=""
PROJECT_NAME=""
EXPECTED_RELEASE_VERSION=""
CONFIRMED_BACKUP_ID=""
COMPOSE_FILE="${ROOT_DIR}/deploy/docker-compose.yml"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --archive) ARCHIVE="${2:?缺少备份归档}"; shift 2 ;;
    --env-file) ENV_FILE="${2:?缺少环境文件}"; shift 2 ;;
    --project-name) PROJECT_NAME="${2:?缺少项目名}"; shift 2 ;;
    --expected-release-version) EXPECTED_RELEASE_VERSION="${2:?缺少预期发行版本}"; shift 2 ;;
    --confirm-backup-id) CONFIRMED_BACKUP_ID="${2:?缺少备份 ID}"; shift 2 ;;
    *) recovery_fail "未知参数 $1" ;;
  esac
done

[[ -n "${ARCHIVE}" ]] || recovery_fail "缺少 --archive"
[[ -n "${ENV_FILE}" ]] || recovery_fail "缺少 --env-file"
[[ -n "${PROJECT_NAME}" ]] || recovery_fail "缺少 --project-name"
[[ -n "${CONFIRMED_BACKUP_ID}" ]] || recovery_fail "必须使用 --confirm-backup-id 精确确认待恢复备份"
recovery_require_project_name "${PROJECT_NAME}"
recovery_require_backup_id "${CONFIRMED_BACKUP_ID}"
[[ -f "${ARCHIVE}" ]] || recovery_fail "备份归档不存在 ${ARCHIVE}"
[[ -f "${ENV_FILE}" ]] || recovery_fail "环境文件不存在 ${ENV_FILE}"
[[ -f "${COMPOSE_FILE}" ]] || recovery_fail "Compose 文件不存在 ${COMPOSE_FILE}"

ROOT_RELEASE_VERSION=""
if [[ -f "${ROOT_DIR}/VERSION" ]]; then
  IFS= read -r ROOT_RELEASE_VERSION < "${ROOT_DIR}/VERSION"
  recovery_require_version "${ROOT_RELEASE_VERSION}"
fi
if [[ -z "${EXPECTED_RELEASE_VERSION}" ]]; then
  [[ -n "${ROOT_RELEASE_VERSION}" ]] \
    || recovery_fail "缺少 --expected-release-version，且发行根目录没有 VERSION"
  EXPECTED_RELEASE_VERSION="${ROOT_RELEASE_VERSION}"
elif [[ -n "${ROOT_RELEASE_VERSION}" && "${EXPECTED_RELEASE_VERSION}" != "${ROOT_RELEASE_VERSION}" ]]; then
  recovery_fail "显式发行版本与 VERSION 不一致"
fi
recovery_require_version "${EXPECTED_RELEASE_VERSION}"

for command in docker jq tar awk sort mktemp dirname basename rm grep; do
  recovery_require_command "${command}"
done
if ! command -v sha256sum >/dev/null 2>&1; then
  recovery_require_command shasum
fi

ARCHIVE_DIR="$(cd "$(dirname "${ARCHIVE}")" && pwd)"
ARCHIVE="${ARCHIVE_DIR}/$(basename "${ARCHIVE}")"
EXTERNAL_CHECKSUM="${ARCHIVE}.sha256"
[[ -f "${EXTERNAL_CHECKSUM}" ]] || recovery_fail "缺少外部校验和 ${EXTERNAL_CHECKSUM}"

if ! checksum_record="$(awk '
  NF != 2 { invalid = 1 }
  { line_count += 1 }
  NF == 2 { record = $1 "|" $2 }
  END {
    if (invalid || line_count != 1) {
      exit 1
    }
    print record
  }
' "${EXTERNAL_CHECKSUM}")"; then
  recovery_fail "外部校验和文件格式无效"
fi
external_hash="${checksum_record%%|*}"
external_name="${checksum_record#*|}"
[[ "${external_hash}" =~ ^[a-f0-9]{64}$ ]] || recovery_fail "外部 SHA-256 格式无效"
[[ "${external_name}" == "$(basename "${ARCHIVE}")" ]] \
  || recovery_fail "外部校验和文件名与备份归档不一致"
[[ "$(recovery_hash_path "${ARCHIVE}")" == "${external_hash}" ]] \
  || recovery_fail "备份归档外部 SHA-256 校验失败"

WORK_DIR="$(mktemp -d)"
CREATED_VOLUMES=()
cleanup_restore() {
  local status="$?"
  rm -rf "${WORK_DIR}"
  if [[ "${status}" -ne 0 && "${#CREATED_VOLUMES[@]}" -gt 0 ]]; then
    echo "WARN: 恢复中断，以下新卷已保留且未自动删除：${CREATED_VOLUMES[*]}" >&2
  fi
}
trap cleanup_restore EXIT

TAR_LIST="${WORK_DIR}/archive.list"
tar -tf "${ARCHIVE}" > "${TAR_LIST}"
[[ -s "${TAR_LIST}" ]] || recovery_fail "备份归档没有任何条目"
EXPECTED_ROOT="mine-standalone-backup-${CONFIRMED_BACKUP_ID}"
while IFS= read -r entry || [[ -n "${entry}" ]]; do
  [[ -n "${entry}" ]] || recovery_fail "备份归档包含空路径"
  [[ "${entry}" != /* ]] || recovery_fail "备份归档包含绝对路径"
  [[ "${entry}" != *"//"* ]] || recovery_fail "备份归档包含空路径段"
  [[ ! "${entry}" =~ (^|/)(\.|\.\.)(/|$) ]] \
    || recovery_fail "备份归档包含目录穿越路径"
  entry_root="${entry%%/*}"
  [[ "${entry_root}" == "${EXPECTED_ROOT}" ]] \
    || recovery_fail "备份归档必须只有与确认 ID 匹配的单一根目录"
done < "${TAR_LIST}"

TAR_VERBOSE_LIST="${WORK_DIR}/archive.verbose.list"
tar -tvf "${ARCHIVE}" > "${TAR_VERBOSE_LIST}"
awk '
  substr($0, 1, 1) != "-" && substr($0, 1, 1) != "d" { invalid = 1 }
  END { exit invalid }
' "${TAR_VERBOSE_LIST}" || recovery_fail "备份归档包含链接或特殊文件条目"

EXTRACT_DIR="${WORK_DIR}/extracted"
mkdir -p "${EXTRACT_DIR}"
tar -xf "${ARCHIVE}" -C "${EXTRACT_DIR}"
BACKUP_ROOT="${EXTRACT_DIR}/${EXPECTED_ROOT}"
MANIFEST="${BACKUP_ROOT}/manifest.json"
INTERNAL_CHECKSUM="${BACKUP_ROOT}/manifests/checksums.sha256"
[[ -f "${MANIFEST}" ]] || recovery_fail "备份缺少 manifest.json"
[[ -f "${INTERNAL_CHECKSUM}" ]] || recovery_fail "备份缺少内部校验和"

checksum_paths=(
  manifest.json
  volumes/postgres_data.tar
  volumes/timescale_data.tar
  volumes/rabbitmq_data.tar
  volumes/rustfs_data.tar
)
awk 'NF != 2 { invalid = 1 } END { exit invalid || NR != 5 }' "${INTERNAL_CHECKSUM}" \
  || recovery_fail "内部校验和必须精确覆盖 manifest 和四个事实卷"
for relative_path in "${checksum_paths[@]}"; do
  expected_internal_hash="$(awk -v path="${relative_path}" '$2 == path {print $1}' "${INTERNAL_CHECKSUM}")"
  [[ -n "${expected_internal_hash}" && "${expected_internal_hash}" != *$'\n'* \
    && "${expected_internal_hash}" =~ ^[a-f0-9]{64}$ ]] \
    || recovery_fail "内部校验和条目无效：${relative_path}"
  [[ -s "${BACKUP_ROOT}/${relative_path}" ]] \
    || recovery_fail "备份事实文件缺失或为空：${relative_path}"
  [[ "$(recovery_hash_path "${BACKUP_ROOT}/${relative_path}")" == "${expected_internal_hash}" ]] \
    || recovery_fail "内部 SHA-256 校验失败：${relative_path}"
done

logical_volumes=(postgres_data timescale_data rabbitmq_data rustfs_data)
services=(postgres timescale rabbitmq rustfs)
jq -e \
  --arg backup_id "${CONFIRMED_BACKUP_ID}" \
  --arg release_version "${EXPECTED_RELEASE_VERSION}" \
  '(.schemaVersion == 1)
   and (.backupId == $backup_id)
   and (.releaseVersion == $release_version)
   and (.createdAt | type == "string")
   and (.sourceProject | type == "string" and length > 0)
   and (.composeSha256 | type == "string" and test("^[a-f0-9]{64}$"))
   and (.services | type == "object" and keys == ["postgres", "rabbitmq", "rustfs", "timescale"])
   and (all(.services[]; (.image | type == "string" and length > 0)
       and (.imageId | type == "string" and length > 0)))
   and (.volumes | type == "array" and length == 4)
   and (.volumes | map(.logicalName) == ["postgres_data", "timescale_data", "rabbitmq_data", "rustfs_data"])
   and (all(.volumes[]; .driver == "local"
       and (.sourceName | type == "string" and length > 0)
       and .archive == ("volumes/" + .logicalName + ".tar")))' \
  "${MANIFEST}" >/dev/null || recovery_fail "备份 manifest 与同版本恢复契约不一致"

COMPOSE_CONFIG="${WORK_DIR}/compose.json"
compose_args=(-p "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
docker compose "${compose_args[@]}" config --format json > "${COMPOSE_CONFIG}"
RUNNING_SERVICES="$(docker compose "${compose_args[@]}" ps --status running --services)"
[[ -z "${RUNNING_SERVICES}" ]] \
  || recovery_fail "目标 Compose 仍有运行服务；恢复脚本不会自行停止服务"

target_images=()
for service in "${services[@]}"; do
  target_image="$(jq -er --arg service "${service}" '.services[$service].image' "${COMPOSE_CONFIG}")" \
    || recovery_fail "目标 Compose 缺少 ${service} 镜像"
  backup_image="$(jq -er --arg service "${service}" '.services[$service].image' "${MANIFEST}")" \
    || recovery_fail "备份 manifest 缺少 ${service} 镜像"
  [[ "${target_image}" == "${backup_image}" ]] \
    || recovery_fail "目标 ${service} 镜像引用与备份不一致"
  target_image_id="$(docker image inspect "${target_image}" --format '{{.Id}}')" \
    || recovery_fail "本地缺少目标 ${service} 镜像"
  backup_image_id="$(jq -er --arg service "${service}" '.services[$service].imageId' "${MANIFEST}")" \
    || recovery_fail "备份 manifest 缺少 ${service} 镜像 ID"
  [[ "${target_image_id}" == "${backup_image_id}" ]] \
    || recovery_fail "目标 ${service} 镜像 ID 与备份不一致"
  target_images+=("${target_image}")
done

EXISTING_VOLUMES="${WORK_DIR}/existing-volumes.list"
docker volume ls --quiet > "${EXISTING_VOLUMES}" \
  || recovery_fail "无法读取 Docker 卷清单，拒绝恢复"
for logical_name in "${logical_volumes[@]}"; do
  target_volume="${PROJECT_NAME}_${logical_name}"
  if grep -Fxq -- "${target_volume}" "${EXISTING_VOLUMES}"; then
    recovery_fail "目标卷已存在，拒绝覆盖：${target_volume}"
  fi
done

HELPER_IMAGE="${target_images[0]}"
for logical_name in "${logical_volumes[@]}"; do
  target_volume="${PROJECT_NAME}_${logical_name}"
  created_volume="$(docker volume create --driver local "${target_volume}")" \
    || recovery_fail "无法创建目标卷：${target_volume}"
  [[ "${created_volume}" == "${target_volume}" ]] \
    || recovery_fail "Docker 返回了非预期目标卷：${created_volume}"
  CREATED_VOLUMES+=("${target_volume}")
  docker run --rm --network none --entrypoint /bin/sh \
    -v "${target_volume}:/target" \
    -v "${BACKUP_ROOT}/volumes/${logical_name}.tar:/restore/source.tar:ro" \
    "${HELPER_IMAGE}" -c 'cd /target && tar -xpf /restore/source.tar'
done

echo "PASS: 已恢复备份 ${CONFIRMED_BACKUP_ID} 到新项目 ${PROJECT_NAME}"
echo "目标卷：${CREATED_VOLUMES[*]}"
echo "尚未启动服务；请准备匹配的 deploy/.env 与证书后人工启动并完成数据库、队列、对象和 API 对账。"
