#!/usr/bin/env bash

# Standalone 冷备份与恢复脚本共享的无副作用校验函数。

recovery_fail() {
  echo "FAIL: $*" >&2
  exit 1
}

recovery_require_command() {
  command -v "$1" >/dev/null 2>&1 || recovery_fail "缺少命令 $1"
}

recovery_hash_path() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  else
    shasum -a 256 "${path}" | awk '{print $1}'
  fi
}

recovery_require_project_name() {
  local project_name="$1"
  [[ "${project_name}" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] \
    || recovery_fail "项目名只能包含小写字母、数字、下划线和连字符，且长度不超过 63"
}

recovery_require_version() {
  local version="$1"
  [[ "${version}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] \
    || recovery_fail "发行版本只能包含字母、数字、点、下划线和连字符，且长度不超过 64"
}

recovery_require_backup_id() {
  local backup_id="$1"
  [[ "${backup_id}" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{8}$ ]] \
    || recovery_fail "备份 ID 格式无效"
}
