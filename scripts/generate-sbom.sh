#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${MINE_SBOM_OUTPUT_DIR:-${ROOT_DIR}/target/sbom}"
CYCLONEDX_MAVEN_PLUGIN_VERSION="${CYCLONEDX_MAVEN_PLUGIN_VERSION:-2.9.2}"
PNPM_TREE_FILE="$(mktemp "${TMPDIR:-/tmp}/mine-pnpm-tree.XXXXXX.json")"
trap 'rm -f "${PNPM_TREE_FILE}"' EXIT

for command in node pnpm jq; do
  command -v "${command}" >/dev/null || { echo "FAIL: 缺少命令 ${command}" >&2; exit 1; }
done

mkdir -p "${OUTPUT_DIR}"

"${ROOT_DIR}/mvnw" -q \
  "org.cyclonedx:cyclonedx-maven-plugin:${CYCLONEDX_MAVEN_PLUGIN_VERSION}:makeAggregateBom" \
  -DoutputDirectory="${OUTPUT_DIR}" \
  -DoutputName=backend \
  -DoutputFormat=json \
  -DincludeBomSerialNumber=false \
  -DincludeLicenseText=false

pnpm --dir "${ROOT_DIR}/mine-web" list --json --depth Infinity > "${PNPM_TREE_FILE}"
node "${ROOT_DIR}/scripts/generate-frontend-sbom.mjs" \
  "${PNPM_TREE_FILE}" "${OUTPUT_DIR}/frontend.json"

for bom in "${OUTPUT_DIR}/backend.json" "${OUTPUT_DIR}/frontend.json"; do
  jq -e '.bomFormat == "CycloneDX" and .specVersion == "1.6" and (.components | length > 0)' \
    "${bom}" >/dev/null
  forbidden="$(jq -r '
    .components[]
    | . as $component
    | (.licenses // [])[]
    | (.expression // .license.id // .license.name // "") as $license
    | select($license | test("AGPL|SSPL|Commons Clause"; "i"))
    | "\($component.name)@\($component.version): \($license)"
  ' "${bom}")"
  if [[ -n "${forbidden}" ]]; then
    echo "FAIL: ${bom} 发现禁止自动引入的许可证：" >&2
    echo "${forbidden}" >&2
    exit 1
  fi
done

echo "PASS: 已生成后端和前端 CycloneDX SBOM，且未发现 AGPL、SSPL 或 Commons Clause 依赖"
