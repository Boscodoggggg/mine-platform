#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${MINE_ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
VERSION=""
OUTPUT_DIR="${ROOT_DIR}/target/offline"
ENV_FILE="${ROOT_DIR}/deploy/env/standalone.env.example"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="${2:?缺少版本号}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:?缺少输出目录}"; shift 2 ;;
    --env-file) ENV_FILE="${2:?缺少环境文件}"; shift 2 ;;
    *) echo "FAIL: 未知参数 $1" >&2; exit 2 ;;
  esac
done

[[ "${VERSION}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || {
  echo "FAIL: --version 只能包含字母、数字、点、下划线和连字符" >&2
  exit 2
}

for command in docker tar find sort awk jq; do
  command -v "${command}" >/dev/null || { echo "FAIL: 缺少命令 ${command}" >&2; exit 1; }
done

required_files=(
  LICENSE NOTICE THIRD-PARTY-NOTICES.md
  deploy/compose/compose-standalone.yml
  deploy/env/standalone.env.example
  mine-server/target/mine-server-0.1.0-SNAPSHOT.jar
  mine-gateway/target/mine-gateway-0.1.0-SNAPSHOT.jar
  mine-telemetry/target/mine-telemetry-0.1.0-SNAPSHOT.jar
  mine-web/dist/index.html
  target/sbom/backend.json
  target/sbom/frontend.json
)
for relative in "${required_files[@]}"; do
  [[ -s "${ROOT_DIR}/${relative}" ]] || { echo "FAIL: 缺少发行文件 ${relative}" >&2; exit 1; }
done
[[ -f "${ENV_FILE}" ]] || { echo "FAIL: 环境文件不存在 ${ENV_FILE}" >&2; exit 1; }
for sbom in backend frontend; do
  jq -e '.bomFormat == "CycloneDX" and (.specVersion | type == "string")' \
    "${ROOT_DIR}/target/sbom/${sbom}.json" >/dev/null \
    || { echo "FAIL: ${sbom} SBOM 不是有效的 CycloneDX JSON" >&2; exit 1; }
done

bundle_name="mine-platform-${VERSION}"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
bundle_dir="${work_dir}/${bundle_name}"
mkdir -p "${bundle_dir}"/{deploy,images,manifests,mine-server/target,mine-gateway/target,mine-telemetry/target,mine-web,sbom}

cp "${ROOT_DIR}/LICENSE" "${ROOT_DIR}/NOTICE" "${ROOT_DIR}/THIRD-PARTY-NOTICES.md" "${bundle_dir}/"
printf '%s\n' "${VERSION}" > "${bundle_dir}/VERSION"
cp -R "${ROOT_DIR}/deploy/compose" "${ROOT_DIR}/deploy/config" \
  "${ROOT_DIR}/deploy/scripts" "${ROOT_DIR}/deploy/tests" "${ROOT_DIR}/deploy/secrets.example" "${bundle_dir}/deploy/"
mkdir -p "${bundle_dir}/deploy/env"
cp "${ROOT_DIR}/deploy/env/standalone.env.example" "${bundle_dir}/deploy/env/"
cp "${ROOT_DIR}/mine-server/target/mine-server-0.1.0-SNAPSHOT.jar" "${bundle_dir}/mine-server/target/"
cp "${ROOT_DIR}/mine-gateway/target/mine-gateway-0.1.0-SNAPSHOT.jar" "${bundle_dir}/mine-gateway/target/"
cp "${ROOT_DIR}/mine-telemetry/target/mine-telemetry-0.1.0-SNAPSHOT.jar" "${bundle_dir}/mine-telemetry/target/"
cp -R "${ROOT_DIR}/mine-web/dist" "${bundle_dir}/mine-web/"
cp "${ROOT_DIR}/target/sbom/backend.json" "${ROOT_DIR}/target/sbom/frontend.json" "${bundle_dir}/sbom/"

images=()
while IFS= read -r image; do
  [[ -n "${image}" ]] && images+=("${image}")
done < <(docker compose --profile observability --env-file "${ENV_FILE}" \
  -f "${ROOT_DIR}/deploy/compose/compose-standalone.yml" config --images | LC_ALL=C sort -u)
(( ${#images[@]} > 0 )) || { echo "FAIL: Compose 未解析出镜像" >&2; exit 1; }

: > "${bundle_dir}/manifests/images.lock"
for image in "${images[@]}"; do
  image_id="$(docker image inspect "${image}" --format '{{.Id}}')" || {
    echo "FAIL: 本地缺少镜像 ${image}" >&2
    exit 1
  }
  printf '%s\t%s\n' "${image}" "${image_id}" >> "${bundle_dir}/manifests/images.lock"
done
docker save --output "${bundle_dir}/images/images.tar" "${images[@]}"

hash_path() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

(
  cd "${bundle_dir}"
  find . -type f ! -path './manifests/checksums.sha256' | LC_ALL=C sort | while IFS= read -r file; do
    printf '%s  %s\n' "$(hash_path "${file}")" "${file}"
  done > manifests/checksums.sha256
)

mkdir -p "${OUTPUT_DIR}"
archive="${OUTPUT_DIR}/${bundle_name}.tar"
tar -cf "${archive}" -C "${work_dir}" "${bundle_name}"
printf '%s  %s\n' "$(hash_path "${archive}")" "$(basename "${archive}")" > "${archive}.sha256"
echo "PASS: 已生成离线包 ${archive}"
