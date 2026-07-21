#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${MINE_ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON_LIB="${SCRIPT_DIR}/lib/standalone-recovery-common.sh"
[[ -f "${COMMON_LIB}" ]] || { echo "FAIL: 缺少公共恢复库 ${COMMON_LIB}" >&2; exit 1; }
# shellcheck source=deploy/scripts/lib/standalone-recovery-common.sh
source "${COMMON_LIB}"

ENV_FILE=""
PROJECT_NAME=""
RELEASE_VERSION=""
OUTPUT_DIR=""
SECURE_DESTINATION_CONFIRMED=false
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/compose-standalone.yml"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) ENV_FILE="${2:?缺少环境文件}"; shift 2 ;;
    --project-name) PROJECT_NAME="${2:?缺少项目名}"; shift 2 ;;
    --release-version) RELEASE_VERSION="${2:?缺少发行版本}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:?缺少输出目录}"; shift 2 ;;
    --confirm-secure-destination) SECURE_DESTINATION_CONFIRMED=true; shift ;;
    *) recovery_fail "未知参数 $1" ;;
  esac
done

[[ "${SECURE_DESTINATION_CONFIRMED}" == "true" ]] \
  || recovery_fail "必须使用 --confirm-secure-destination 确认目标目录已受控并加密"
[[ -n "${ENV_FILE}" ]] || recovery_fail "缺少 --env-file"
[[ -n "${PROJECT_NAME}" ]] || recovery_fail "缺少 --project-name"
[[ -n "${RELEASE_VERSION}" ]] || recovery_fail "缺少 --release-version"
[[ -n "${OUTPUT_DIR}" ]] || recovery_fail "缺少 --output-dir"
recovery_require_project_name "${PROJECT_NAME}"
recovery_require_version "${RELEASE_VERSION}"
[[ -f "${ENV_FILE}" ]] || recovery_fail "环境文件不存在 ${ENV_FILE}"
[[ -f "${COMPOSE_FILE}" ]] || recovery_fail "Compose 文件不存在 ${COMPOSE_FILE}"

for command in docker jq tar awk find sort date mktemp mv; do
  recovery_require_command "${command}"
done
if ! command -v sha256sum >/dev/null 2>&1; then
  recovery_require_command shasum
fi

BACKUP_CREATED_AT="${MINE_BACKUP_CREATED_AT:-$(date -u '+%Y-%m-%dT%H:%M:%SZ')}"
if [[ -n "${MINE_BACKUP_ID:-}" ]]; then
  BACKUP_ID="${MINE_BACKUP_ID}"
else
  recovery_require_command openssl
  BACKUP_ID="$(date -u '+%Y%m%dT%H%M%SZ')-$(openssl rand -hex 4)"
fi
recovery_require_backup_id "${BACKUP_ID}"
[[ "${BACKUP_CREATED_AT}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
  || recovery_fail "备份创建时间必须是 UTC RFC3339 秒级时间"

mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR="$(cd "${OUTPUT_DIR}" && pwd)"
ARCHIVE_NAME="mine-standalone-backup-${RELEASE_VERSION}-${BACKUP_ID}.tar"
FINAL_ARCHIVE="${OUTPUT_DIR}/${ARCHIVE_NAME}"
FINAL_CHECKSUM="${FINAL_ARCHIVE}.sha256"
[[ ! -e "${FINAL_ARCHIVE}" && ! -e "${FINAL_CHECKSUM}" ]] \
  || recovery_fail "同名备份输出已存在，拒绝覆盖"

WORK_DIR="$(mktemp -d "${OUTPUT_DIR}/.mine-backup.XXXXXX")"
trap 'rm -rf "${WORK_DIR}"' EXIT
BACKUP_ROOT_NAME="mine-standalone-backup-${BACKUP_ID}"
BACKUP_ROOT="${WORK_DIR}/${BACKUP_ROOT_NAME}"
mkdir -p "${BACKUP_ROOT}/volumes" "${BACKUP_ROOT}/manifests"

COMPOSE_CONFIG="${WORK_DIR}/compose.json"
compose_args=(-p "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
docker compose "${compose_args[@]}" config --format json > "${COMPOSE_CONFIG}"

RUNNING_SERVICES="$(docker compose "${compose_args[@]}" ps --status running --services)"
[[ -z "${RUNNING_SERVICES}" ]] \
  || recovery_fail "检测到仍在运行的 Compose 服务；请先停止全部服务再执行冷备份"

POSTGRES_IMAGE="$(jq -er '.services.postgres.image' "${COMPOSE_CONFIG}")" \
  || recovery_fail "Compose 缺少 postgres 镜像"
TIMESCALE_IMAGE="$(jq -er '.services.timescale.image' "${COMPOSE_CONFIG}")" \
  || recovery_fail "Compose 缺少 timescale 镜像"
RABBITMQ_IMAGE="$(jq -er '.services.rabbitmq.image' "${COMPOSE_CONFIG}")" \
  || recovery_fail "Compose 缺少 rabbitmq 镜像"
RUSTFS_IMAGE="$(jq -er '.services.rustfs.image' "${COMPOSE_CONFIG}")" \
  || recovery_fail "Compose 缺少 rustfs 镜像"

POSTGRES_IMAGE_ID="$(docker image inspect "${POSTGRES_IMAGE}" --format '{{.Id}}')" \
  || recovery_fail "本地缺少 postgres 镜像"
TIMESCALE_IMAGE_ID="$(docker image inspect "${TIMESCALE_IMAGE}" --format '{{.Id}}')" \
  || recovery_fail "本地缺少 timescale 镜像"
RABBITMQ_IMAGE_ID="$(docker image inspect "${RABBITMQ_IMAGE}" --format '{{.Id}}')" \
  || recovery_fail "本地缺少 rabbitmq 镜像"
RUSTFS_IMAGE_ID="$(docker image inspect "${RUSTFS_IMAGE}" --format '{{.Id}}')" \
  || recovery_fail "本地缺少 rustfs 镜像"

logical_volumes=(postgres_data timescale_data rabbitmq_data rustfs_data)
for logical_name in "${logical_volumes[@]}"; do
  source_name="${PROJECT_NAME}_${logical_name}"
  if ! driver="$(docker volume inspect "${source_name}" --format '{{.Driver}}')"; then
    recovery_fail "事实卷不存在：${source_name}"
  fi
  [[ "${driver}" == "local" ]] || recovery_fail "事实卷不是 local 驱动：${source_name}"
done

for logical_name in "${logical_volumes[@]}"; do
  source_name="${PROJECT_NAME}_${logical_name}"
  docker run --rm --network none --entrypoint /bin/sh \
    -v "${source_name}:/source:ro" \
    -v "${BACKUP_ROOT}/volumes:/backup" \
    "${POSTGRES_IMAGE}" -c "tar -cpf /backup/${logical_name}.tar -C /source ."
  [[ -s "${BACKUP_ROOT}/volumes/${logical_name}.tar" ]] \
    || recovery_fail "事实卷归档为空：${logical_name}"
done

COMPOSE_SHA256="$(recovery_hash_path "${COMPOSE_FILE}")"
jq -n \
  --arg backup_id "${BACKUP_ID}" \
  --arg created_at "${BACKUP_CREATED_AT}" \
  --arg release_version "${RELEASE_VERSION}" \
  --arg source_project "${PROJECT_NAME}" \
  --arg compose_sha256 "${COMPOSE_SHA256}" \
  --arg postgres_image "${POSTGRES_IMAGE}" \
  --arg postgres_image_id "${POSTGRES_IMAGE_ID}" \
  --arg timescale_image "${TIMESCALE_IMAGE}" \
  --arg timescale_image_id "${TIMESCALE_IMAGE_ID}" \
  --arg rabbitmq_image "${RABBITMQ_IMAGE}" \
  --arg rabbitmq_image_id "${RABBITMQ_IMAGE_ID}" \
  --arg rustfs_image "${RUSTFS_IMAGE}" \
  --arg rustfs_image_id "${RUSTFS_IMAGE_ID}" \
  --arg postgres_source "${PROJECT_NAME}_postgres_data" \
  --arg timescale_source "${PROJECT_NAME}_timescale_data" \
  --arg rabbitmq_source "${PROJECT_NAME}_rabbitmq_data" \
  --arg rustfs_source "${PROJECT_NAME}_rustfs_data" \
  '{
    schemaVersion: 1,
    backupId: $backup_id,
    createdAt: $created_at,
    releaseVersion: $release_version,
    sourceProject: $source_project,
    composeSha256: $compose_sha256,
    services: {
      postgres: {image: $postgres_image, imageId: $postgres_image_id},
      timescale: {image: $timescale_image, imageId: $timescale_image_id},
      rabbitmq: {image: $rabbitmq_image, imageId: $rabbitmq_image_id},
      rustfs: {image: $rustfs_image, imageId: $rustfs_image_id}
    },
    volumes: [
      {logicalName: "postgres_data", sourceName: $postgres_source, driver: "local", archive: "volumes/postgres_data.tar"},
      {logicalName: "timescale_data", sourceName: $timescale_source, driver: "local", archive: "volumes/timescale_data.tar"},
      {logicalName: "rabbitmq_data", sourceName: $rabbitmq_source, driver: "local", archive: "volumes/rabbitmq_data.tar"},
      {logicalName: "rustfs_data", sourceName: $rustfs_source, driver: "local", archive: "volumes/rustfs_data.tar"}
    ]
  }' > "${BACKUP_ROOT}/manifest.json"

(
  cd "${BACKUP_ROOT}"
  checksum_paths=(manifest.json volumes/postgres_data.tar volumes/timescale_data.tar volumes/rabbitmq_data.tar volumes/rustfs_data.tar)
  for path in "${checksum_paths[@]}"; do
    printf '%s  %s\n' "$(recovery_hash_path "${path}")" "${path}"
  done > manifests/checksums.sha256
)

TEMP_ARCHIVE="${WORK_DIR}/${ARCHIVE_NAME}.partial"
tar -cf "${TEMP_ARCHIVE}" -C "${WORK_DIR}" "${BACKUP_ROOT_NAME}"
TEMP_CHECKSUM="${WORK_DIR}/${ARCHIVE_NAME}.sha256.partial"
printf '%s  %s\n' "$(recovery_hash_path "${TEMP_ARCHIVE}")" "${ARCHIVE_NAME}" > "${TEMP_CHECKSUM}"
mv "${TEMP_ARCHIVE}" "${FINAL_ARCHIVE}"
mv "${TEMP_CHECKSUM}" "${FINAL_CHECKSUM}"

echo "PASS: 已生成单机冷备份 ${FINAL_ARCHIVE}（backupId=${BACKUP_ID}，服务保持停止）"
