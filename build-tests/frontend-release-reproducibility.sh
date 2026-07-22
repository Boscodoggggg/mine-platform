#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGER="${ROOT_DIR}/mine-web/scripts/package-release.mjs"
ARTIFACT="${ROOT_DIR}/release/mine-web.zip"

hash_file() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

TZ=UTC node "${PACKAGER}" >/dev/null
utc_hash="$(hash_file "${ARTIFACT}")"
TZ=Asia/Shanghai node "${PACKAGER}" >/dev/null
shanghai_hash="$(hash_file "${ARTIFACT}")"

[[ "${utc_hash}" == "${shanghai_hash}" ]] || {
  echo "FAIL: 相同前端内容在不同时区生成的 ZIP 摘要不一致" >&2
  exit 1
}

echo "PASS: 前端 ZIP 在 UTC 与 Asia/Shanghai 下可重复构建"
