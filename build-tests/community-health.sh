#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

required_files=(
  README.md
  README.zh-CN.md
  CONTRIBUTING.md
  SECURITY.md
  CODE_OF_CONDUCT.md
  LICENSE
  .github/CODEOWNERS
  .github/PULL_REQUEST_TEMPLATE.md
  .github/ISSUE_TEMPLATE/bug_report.yml
  .github/ISSUE_TEMPLATE/feature_request.yml
  .github/ISSUE_TEMPLATE/config.yml
  docs/13-branching-and-release.md
)

for relative_path in "${required_files[@]}"; do
  [[ -s "${ROOT_DIR}/${relative_path}" ]] || fail "缺少开源社区文件 ${relative_path}"
done

required_english_links=(
  README.zh-CN.md
  CONTRIBUTING.md
  SECURITY.md
  LICENSE
  docs/README.md
  deploy/README.md
)
for target in "${required_english_links[@]}"; do
  grep -Fq "](${target})" "${ROOT_DIR}/README.md" \
    || fail "英文 README 缺少 ${target} 入口"
done

required_chinese_links=(
  README.md
  CONTRIBUTING.md
  SECURITY.md
  LICENSE
  docs/README.md
  deploy/README.md
)
for target in "${required_chinese_links[@]}"; do
  grep -Fq "](${target})" "${ROOT_DIR}/README.zh-CN.md" \
    || fail "中文 README 缺少 ${target} 入口"
done

for issue_form in bug_report.yml feature_request.yml; do
  issue_path="${ROOT_DIR}/.github/ISSUE_TEMPLATE/${issue_form}"
  grep -Eq '^name: .+' "${issue_path}" || fail "${issue_form} 缺少 name"
  grep -Eq '^description: .+' "${issue_path}" || fail "${issue_form} 缺少 description"
  grep -Eq '^body:' "${issue_path}" || fail "${issue_form} 缺少 body"
done

community_files=(
  README.md
  README.zh-CN.md
  CONTRIBUTING.md
  SECURITY.md
  CODE_OF_CONDUCT.md
  .github/PULL_REQUEST_TEMPLATE.md
)
for relative_path in "${community_files[@]}"; do
  if grep -Eiq 'TODO|CHANGEME|YOUR[_ -]?(NAME|REPO|ORG)|<owner>|<repository>' \
    "${ROOT_DIR}/${relative_path}"; then
    fail "${relative_path} 包含未替换占位符"
  fi
done

while IFS=: read -r source_file markdown_link; do
  [[ -n "${markdown_link}" ]] || continue
  case "${markdown_link}" in
    http://*|https://*|mailto:*|\#*) continue ;;
  esac
  link_path="${markdown_link%%#*}"
  [[ -n "${link_path}" ]] || continue
  source_dir="$(dirname "${source_file}")"
  [[ -e "${source_dir}/${link_path}" ]] \
    || fail "${source_file#${ROOT_DIR}/} 存在失效链接 ${markdown_link}"
done < <(
  for relative_path in "${community_files[@]}"; do
    source_file="${ROOT_DIR}/${relative_path}"
    grep -Eo '\]\([^)]*\)' "${source_file}" \
      | sed -E 's/^\]\((.*)\)$/\1/' \
      | while IFS= read -r markdown_link; do
          printf '%s:%s\n' "${source_file}" "${markdown_link}"
        done
  done
)

echo "PASS: 开源社区文件、双语入口、模板和相对链接完整"
