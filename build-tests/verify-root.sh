#!/usr/bin/env bash

set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pom_file="${root_dir}/pom.xml"

if [[ ! -f "${pom_file}" ]]; then
  echo "FAIL: pom.xml 不存在" >&2
  exit 1
fi

if ! grep -q '<packaging>pom</packaging>' "${pom_file}"; then
  echo "FAIL: 根工程 packaging 必须为 pom" >&2
  exit 1
fi

required_modules=(
  mine-common
  mine-device-model
  mine-messaging
  mine-protocol-sdk
  mine-protocol-engine
  mine-gateway
  mine-telemetry
  mine-server
)

for module in "${required_modules[@]}"; do
  if ! grep -q "<module>${module}</module>" "${pom_file}"; then
    echo "FAIL: pom.xml 缺少模块 ${module}" >&2
    exit 1
  fi
done

echo "PASS: 根 Maven 工程结构正确"
