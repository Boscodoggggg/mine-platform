#!/usr/bin/env bash
set -euo pipefail

source_dir="${MINE_TLS_SOURCE_DIR:-/opt/mine/source-certs}"
target_dir="${MINE_TLS_TARGET_DIR:-/etc/mine/certs}"
target_owner="${MINE_TLS_TARGET_OWNER:-}"

[[ "$(id -u)" == "0" ]] || { echo "FAIL: TLS 暂存脚本必须以 root 运行" >&2; exit 1; }
[[ -s "${source_dir}/tls.crt" ]] || { echo "FAIL: TLS 证书缺失：${source_dir}/tls.crt" >&2; exit 1; }
[[ -s "${source_dir}/tls.key" ]] || { echo "FAIL: TLS 私钥缺失：${source_dir}/tls.key" >&2; exit 1; }

if [[ -n "${target_owner}" ]]; then
  target_uid="$(id -u "${target_owner}")"
  target_gid="$(id -g "${target_owner}")"
else
  target_uid="${MINE_TLS_TARGET_UID:?必须配置 MINE_TLS_TARGET_UID 或 MINE_TLS_TARGET_OWNER}"
  target_gid="${MINE_TLS_TARGET_GID:?必须配置 MINE_TLS_TARGET_GID 或 MINE_TLS_TARGET_OWNER}"
fi

install -d -o "${target_uid}" -g "${target_gid}" -m 0750 "${target_dir}"
install -o "${target_uid}" -g "${target_gid}" -m 0444 \
  "${source_dir}/tls.crt" "${target_dir}/tls.crt"
install -o "${target_uid}" -g "${target_gid}" -m 0400 \
  "${source_dir}/tls.key" "${target_dir}/tls.key"

echo "PASS: TLS 文件已暂存到专属运行卷"
