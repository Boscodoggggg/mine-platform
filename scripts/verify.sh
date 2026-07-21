#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deploy/compose/compose-standalone.yml"
ENV_FILE="${ROOT_DIR}/deploy/env/standalone.env.example"

for command in java node pnpm docker jq; do
  command -v "${command}" >/dev/null || { echo "FAIL: 缺少命令 ${command}" >&2; exit 1; }
done

echo "[1/6] 开源社区文件与文档入口"
bash "${ROOT_DIR}/build-tests/community-health.sh"

echo "[2/6] 后端测试、集成测试和架构规则"
"${ROOT_DIR}/mvnw" -B -ntp verify

echo "[3/6] 前端依赖、类型、代码规范、测试和生产构建"
pnpm --dir "${ROOT_DIR}/mine-web" install --frozen-lockfile
pnpm --dir "${ROOT_DIR}/mine-web" typecheck
pnpm --dir "${ROOT_DIR}/mine-web" lint:check
pnpm --dir "${ROOT_DIR}/mine-web" test
pnpm --dir "${ROOT_DIR}/mine-web" build

echo "[4/6] Shell 与 Docker Compose 配置"
find "${ROOT_DIR}/build-tests" "${ROOT_DIR}/scripts" "${ROOT_DIR}/deploy/scripts" "${ROOT_DIR}/deploy/tests" \
  -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
docker compose --profile observability --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --quiet
bash "${ROOT_DIR}/deploy/tests/standalone-backup.sh"
bash "${ROOT_DIR}/deploy/tests/standalone-restore.sh"

echo "[5/6] Prometheus、Loki 与 Alloy 配置"
docker run --rm --entrypoint /bin/promtool \
  -v "${ROOT_DIR}/deploy/config/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.12.0 check config /etc/prometheus/prometheus.yml
bash "${ROOT_DIR}/deploy/tests/prometheus-rules.sh"
docker run --rm \
  -v "${ROOT_DIR}/deploy/config/loki/loki.yml:/etc/loki/loki.yml:ro" \
  grafana/loki:3.7.2 -config.file=/etc/loki/loki.yml -verify-config=true
docker run --rm \
  -v "${ROOT_DIR}/deploy/config/alloy/config.alloy:/etc/alloy/config.alloy:ro" \
  grafana/alloy:v1.16.1 validate /etc/alloy/config.alloy

echo "[6/6] CycloneDX SBOM 与许可证门禁"
bash "${ROOT_DIR}/scripts/generate-sbom.sh"
bash "${ROOT_DIR}/deploy/tests/offline-bundle.sh"

echo "PASS: 社区文档、后端、前端、部署配置、SBOM 与许可证质量门禁全部通过"
