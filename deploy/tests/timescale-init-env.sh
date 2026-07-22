#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
mkdir -p "${work_dir}/bin"
cat >"${work_dir}/bin/psql" <<'FAKE_PSQL'
#!/usr/bin/env bash
set -euo pipefail
[[ "${PGPASSWORD}" == "fixture-writer-password" ]]
cat >/dev/null
FAKE_PSQL
chmod +x "${work_dir}/bin/psql"

PATH="${work_dir}/bin:${PATH}" \
POSTGRES_DB=mine_telemetry \
POSTGRES_USER=mine_ts \
POSTGRES_PASSWORD=fixture-writer-password \
TIMESCALE_READER_PASSWORD=fixture-reader-password \
TIMESCALE_REPLAY_PASSWORD=fixture-replay-password \
  "${ROOT_DIR}/deploy/scripts/timescale-reader-init.sh"

echo "PASS: TimescaleDB 初始化可直接使用标准 POSTGRES_PASSWORD"
