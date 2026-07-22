#!/usr/bin/env bash
set -euo pipefail

BUNDLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE=""
PROJECT_NAME="mine-standalone"
PROFILE=""
LOAD_ONLY=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bundle-dir) BUNDLE_DIR="$(cd "${2:?缺少离线包目录}" && pwd)"; shift 2 ;;
    --env-file) ENV_FILE="${2:?缺少环境文件}"; shift 2 ;;
    --project-name) PROJECT_NAME="${2:?缺少项目名}"; shift 2 ;;
    --profile) PROFILE="${2:?缺少 profile}"; shift 2 ;;
    --load-only) LOAD_ONLY=true; shift ;;
    *) echo "FAIL: 未知参数 $1" >&2; exit 2 ;;
  esac
done

[[ "${PROJECT_NAME}" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] || {
  echo "FAIL: project-name 格式不合法" >&2
  exit 2
}
[[ -z "${PROFILE}" || "${PROFILE}" == "observability" ]] || {
  echo "FAIL: 当前只支持 observability profile" >&2
  exit 2
}
[[ "${PROFILE}" != "observability" || "${PROJECT_NAME}" == "mine-standalone" ]] || {
  echo "FAIL: observability 配置按 mine-standalone 项目标签采集日志，启用该 profile 时项目名必须为 mine-standalone" >&2
  exit 2
}
for command in docker awk sort grep; do
  command -v "${command}" >/dev/null || { echo "FAIL: 缺少命令 ${command}" >&2; exit 1; }
done
if ! command -v sha256sum >/dev/null && ! command -v shasum >/dev/null; then
  echo "FAIL: 缺少命令 sha256sum 或 shasum" >&2
  exit 1
fi
if ! ${LOAD_ONLY}; then
  for command in bash curl openssl df jq stat; do
    command -v "${command}" >/dev/null || { echo "FAIL: 缺少命令 ${command}" >&2; exit 1; }
  done
  [[ "${PROFILE}" != "observability" ]] || command -v seq >/dev/null \
    || { echo "FAIL: 缺少命令 seq" >&2; exit 1; }
fi
checksums="${BUNDLE_DIR}/manifests/checksums.sha256"
images_lock="${BUNDLE_DIR}/manifests/images.lock"
images_tar="${BUNDLE_DIR}/images/images.tar"
[[ -s "${checksums}" && -s "${images_lock}" && -s "${images_tar}" ]] || {
  echo "FAIL: 离线包缺少校验和、镜像锁或镜像归档" >&2
  exit 1
}

if command -v sha256sum >/dev/null; then
  (cd "${BUNDLE_DIR}" && sha256sum --check manifests/checksums.sha256 >/dev/null)
else
  (cd "${BUNDLE_DIR}" && shasum -a 256 --check manifests/checksums.sha256 >/dev/null)
fi

compose=(docker compose -p "${PROJECT_NAME}")
if ! ${LOAD_ONLY}; then
  [[ -n "${ENV_FILE}" ]] || ENV_FILE="${BUNDLE_DIR}/deploy/.env"
  [[ -f "${ENV_FILE}" ]] || {
    echo "FAIL: 缺少现场环境文件 ${ENV_FILE}，请从 deploy/.env.example 复制并配置" >&2
    exit 1
  }
  MINE_ENV_FILE="${ENV_FILE}" MINE_PREFLIGHT_PROFILE="${PROFILE}" \
    "${BUNDLE_DIR}/deploy/scripts/preflight.sh"
  compose+=(--env-file "${ENV_FILE}" -f "${BUNDLE_DIR}/deploy/docker-compose.yml")
  [[ -n "${PROFILE}" ]] && compose+=(--profile "${PROFILE}")

  desired_count=0
  while IFS= read -r image; do
    [[ -n "${image}" ]] || continue
    desired_count=$((desired_count + 1))
    matches="$(awk -F '\t' -v target="${image}" '$1 == target { count++; id=$2 } END { if (count == 1) print id }' "${images_lock}")"
    [[ -n "${matches}" ]] || {
      echo "FAIL: 现场 Compose 镜像未被离线包锁定：${image}" >&2
      exit 1
    }
  done < <("${compose[@]}" config --images | LC_ALL=C sort -u)
  (( desired_count > 0 )) || { echo "FAIL: 现场 Compose 未解析出镜像" >&2; exit 1; }
fi

docker load --input "${images_tar}" >/dev/null

while IFS=$'\t' read -r image expected_id; do
  [[ -n "${image}" && -n "${expected_id}" ]] || { echo "FAIL: images.lock 格式错误" >&2; exit 1; }
  actual_id="$(docker image inspect "${image}" --format '{{.Id}}')"
  [[ "${actual_id}" == "${expected_id}" ]] || {
    echo "FAIL: 镜像 ID 不匹配 ${image}，期望 ${expected_id}，实际 ${actual_id}" >&2
    exit 1
  }
done < "${images_lock}"
echo "PASS: 离线包校验和与镜像 ID 验证通过"

${LOAD_ONLY} && exit 0
"${compose[@]}" up -d --pull never
MINE_ENV_FILE="${ENV_FILE}" MINE_COMPOSE_PROJECT_NAME="${PROJECT_NAME}" \
  "${BUNDLE_DIR}/deploy/scripts/health-check.sh"
if [[ "${PROFILE}" == "observability" ]]; then
  MINE_ENV_FILE="${ENV_FILE}" MINE_COMPOSE_PROJECT_NAME="${PROJECT_NAME}" \
    "${BUNDLE_DIR}/deploy/tests/observability-smoke.sh"
fi
echo "PASS: 离线单机环境 ${PROJECT_NAME} 安装完成"
