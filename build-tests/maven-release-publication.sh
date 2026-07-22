#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

for service in mine-server mine-gateway mine-telemetry; do
  pom="${ROOT_DIR}/${service}/pom.xml"
  grep -Fq 'tofile="${maven.multiModuleProjectDirectory}/release/${project.artifactId}.jar.tmp"' "${pom}" \
    || { echo "FAIL: ${service} 未先复制到 release 临时 JAR" >&2; exit 1; }
  grep -Fq 'file="${maven.multiModuleProjectDirectory}/release/${project.artifactId}.jar.tmp"' "${pom}" \
    || { echo "FAIL: ${service} 未声明临时 JAR 原子移动" >&2; exit 1; }
  grep -Fq 'tofile="${maven.multiModuleProjectDirectory}/release/${project.artifactId}.jar"' "${pom}" \
    || { echo "FAIL: ${service} 未声明最终 release JAR" >&2; exit 1; }
done

echo "PASS: Maven 三个可执行 JAR 均通过临时文件发布到 release"
