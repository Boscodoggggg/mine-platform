# Phase 4 单机同版本备份恢复 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付可离线执行、默认不覆盖、带完整校验和与版本门禁的 Standalone 同版本冷备份和恢复脚本。

**Architecture:** 在全部 Compose 服务停止后，使用离线包已有 PostgreSQL Alpine 镜像以只读、无网络方式归档 PostgreSQL、TimescaleDB、RabbitMQ 和 RustFS 四个事实卷。恢复先验证外部/内部 checksum、manifest、发行版本和镜像 ID，再只写入不存在的新 local 卷；脚本不停止/启动服务、不复制 secret、不覆盖或删除现场卷。

**Tech Stack:** Bash 4+、Docker Engine、Docker Compose v2、jq、tar、SHA-256、现有 Standalone Compose/离线包脚本。

---

实施必须遵守 [单机同版本备份恢复设计](2026-07-20-standalone-backup-restore-design.md)、仓库根 `AGENTS.md` 和 `@test-driven-development`。Shell 生产代码写入前必须先运行对应测试并观察预期失败；不得用真实开发机生产卷做自动化测试。

### Task 1：建立备份脚本失败契约

**Files:**
- Create: `deploy/tests/standalone-backup.sh`
- Test target: `deploy/scripts/backup-standalone.sh`

**Step 1: Write the failing backup contract test**

测试创建临时 fixture：最小 Compose、环境示例、`VERSION`、fake Docker 和四个虚拟卷。fake Docker 固定支持：

```text
docker compose ... config --format json
docker compose ... ps --status running --services
docker image inspect IMAGE --format {{.Id}}
docker volume inspect VOLUME --format {{.Driver}}
docker run ... -v HOST_OUTPUT:/backup ... /backup/LOGICAL.tar
```

断言至少覆盖：

1. 目标脚本存在且可执行。
2. 缺少 `--confirm-secure-destination` 时失败，且不调用 Docker。
3. 任一 Compose 服务运行时失败，且不调用归档容器。
4. 四个事实卷缺任意一个或驱动不是 `local` 时失败。
5. 成功时只归档 `postgres_data/timescale_data/rabbitmq_data/rustfs_data`，源卷挂载含 `:ro`，Docker 参数含 `--network none`。
6. 最终 tar 只有单一根目录，包含 `manifest.json`、`manifests/checksums.sha256` 和四个非空卷归档；不包含 env、secret、Redis、监控、暂存或 Gateway 缓存。
7. manifest schema、backupId、UTC 时间、发行版本、项目、Compose 摘要、四项镜像引用/ID 和卷驱动准确。
8. 内部 checksum 与外部 `${archive}.sha256` 均可复验。
9. 已存在同名最终归档时失败，不覆盖文件。

使用稳定测试注入：

```bash
MINE_BACKUP_ID=20260720T220000Z-a1b2c3d4
MINE_BACKUP_CREATED_AT=2026-07-20T22:00:00Z
```

**Step 2: Run test to verify RED**

Run: `bash deploy/tests/standalone-backup.sh`

Expected: FAIL，明确指出缺少 `deploy/scripts/backup-standalone.sh`。

**Step 3: Commit the RED test**

```bash
git add deploy/tests/standalone-backup.sh
git commit -m "test(deploy): 定义单机冷备份安全契约"
```

### Task 2：实现安全冷备份与 manifest

**Files:**
- Create: `deploy/scripts/lib/standalone-recovery-common.sh`
- Create: `deploy/scripts/backup-standalone.sh`
- Modify: `deploy/tests/standalone-backup.sh`

**Step 1: Implement shared validation helpers**

公共库提供可复用纯函数：

```bash
recovery_fail() { echo "FAIL: $*" >&2; exit 1; }
recovery_require_command() { command -v "$1" >/dev/null || recovery_fail "缺少命令 $1"; }
recovery_hash_path() { sha256sum/shasum; }
recovery_require_project_name() { [[ "$1" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]]; }
recovery_require_version() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; }
recovery_require_backup_id() { [[ "$1" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]{8}$ ]]; }
```

公共库被执行时不得产生副作用；脚本用绝对规范化路径 source，禁止 `eval`。

**Step 2: Implement backup preflight**

接口固定为：

```bash
deploy/scripts/backup-standalone.sh \
  --env-file deploy/env/standalone.env \
  --project-name mine-standalone \
  --release-version 0.1.0 \
  --output-dir /secure-backup \
  --confirm-secure-destination
```

要求：

- `ROOT_DIR` 支持 `MINE_ROOT_DIR` 测试注入。
- 所有用户参数使用数组传给 Docker。
- 先执行 `docker compose ... config --format json`，只把结果保存在临时文件。
- `docker compose ... ps --status running --services` 返回任何服务名即失败。
- 四个服务镜像从 JSON 的 `.services[service].image` 读取，image ID 由 `docker image inspect` 读取。
- 四个实际卷名固定为 `${PROJECT_NAME}_${logicalName}`；`docker volume inspect` 必须存在且 Driver 为 `local`。
- helper image 使用解析出的 PostgreSQL 镜像；归档容器固定 `--rm --network none --entrypoint /bin/sh`，源卷 `:/source:ro`，输出 `:/backup`。

**Step 3: Implement manifest and atomic publication**

manifest 使用 jq 生成，不手拼 JSON：

```json
{
  "schemaVersion": 1,
  "backupId": "20260720T220000Z-a1b2c3d4",
  "createdAt": "2026-07-20T22:00:00Z",
  "releaseVersion": "0.1.0",
  "sourceProject": "mine-standalone",
  "composeSha256": "64 lowercase hex",
  "services": {
    "postgres": {"image": "...", "imageId": "sha256:..."},
    "timescale": {"image": "...", "imageId": "sha256:..."},
    "rabbitmq": {"image": "...", "imageId": "sha256:..."},
    "rustfs": {"image": "...", "imageId": "sha256:..."}
  },
  "volumes": [
    {
      "logicalName": "postgres_data",
      "sourceName": "mine-standalone_postgres_data",
      "driver": "local",
      "archive": "volumes/postgres_data.tar"
    }
  ]
}
```

卷数组严格四项且顺序固定。内部 checksum 覆盖 `manifest.json` 和四个卷 tar；最终 archive 写临时文件，成功后 `mv` 到：

```text
mine-standalone-backup-<releaseVersion>-<backupId>.tar
mine-standalone-backup-<releaseVersion>-<backupId>.tar.sha256
```

任何输出已存在都拒绝。日志不得输出环境内容、secret 或卷内文件名。

**Step 4: Run backup test GREEN**

```bash
bash deploy/tests/standalone-backup.sh
bash -n deploy/scripts/lib/standalone-recovery-common.sh
bash -n deploy/scripts/backup-standalone.sh
```

Expected: PASS，全部备份安全契约通过。

**Step 5: Commit**

```bash
git add deploy/scripts/lib/standalone-recovery-common.sh deploy/scripts/backup-standalone.sh deploy/tests/standalone-backup.sh
git commit -m "feat(deploy): 生成单机同版本冷备份"
```

### Task 3：建立恢复脚本失败契约

**Files:**
- Create: `deploy/tests/standalone-restore.sh`
- Test target: `deploy/scripts/restore-standalone.sh`

**Step 1: Write failing restore test**

复用 Task 2 备份脚本生成有效 fixture archive，扩展 fake Docker 支持 `volume create` 与恢复 `docker run`。断言：

1. 目标脚本存在且可执行。
2. 未提供或错误 `--confirm-backup-id` 时失败，且不创建卷。
3. 外部 checksum 错误、内部 checksum 错误、manifest schema/发行版本/四卷集合错误时失败。
4. tar 列表含绝对路径、`..` 或多个顶层根目录时，在解包/创建卷前失败；用只覆盖 `tar -tf` 的测试替身制造恶意列表。
5. 目标 Compose 有运行服务、镜像引用/ID 漂移、任一目标卷已存在时失败，且不创建任何卷。
6. 成功恢复只创建 `${targetProject}_postgres_data/timescale_data/rabbitmq_data/rustfs_data` 四个 local 卷。
7. 每个恢复容器使用 `--network none`，目标卷挂载 `/target`，对应归档只读挂载；不出现 `compose up/down` 或 `volume rm`。
8. 成功后打印“尚未启动服务”，不会调用健康检查。

**Step 2: Verify RED**

Run: `bash deploy/tests/standalone-restore.sh`

Expected: FAIL，明确指出缺少 `deploy/scripts/restore-standalone.sh`。

**Step 3: Commit RED test**

```bash
git add deploy/tests/standalone-restore.sh
git commit -m "test(deploy): 定义单机恢复拒绝覆盖契约"
```

### Task 4：实现先验证后写入的同版本恢复

**Files:**
- Create: `deploy/scripts/restore-standalone.sh`
- Modify: `deploy/tests/standalone-restore.sh`
- Reuse: `deploy/scripts/lib/standalone-recovery-common.sh`

**Step 1: Implement archive and manifest validation**

接口固定为：

```bash
deploy/scripts/restore-standalone.sh \
  --archive /secure-backup/mine-standalone-backup-0.1.0-<backupId>.tar \
  --env-file deploy/env/standalone.env \
  --project-name mine-restore \
  --expected-release-version 0.1.0 \
  --confirm-backup-id <backupId>
```

规则：

- 如果离线包根存在 `VERSION`，默认读取；显式版本与 `VERSION` 不一致必须失败。
- 外部 checksum 文件固定为 `${archive}.sha256`，校验 basename 必须等于 archive basename。
- `tar -tf` 每行拒绝绝对路径、空路径、`.`/`..` 段和第二个顶层目录；只有验证后才能解包。
- 解包后验证内部 checksum，manifest 必须是 schema 1、确认 ID 精确匹配、发行版本匹配、四项服务和四项卷集合完全一致。
- 每个 `archive` 必须位于 `volumes/<logicalName>.tar`，对应文件非空。

**Step 2: Implement target preflight**

- 解析目标 Compose 四个镜像引用并读取本地 ID，逐项与 manifest 精确匹配。
- 任一目标 Compose 服务运行即失败。
- `docker volume inspect` 任一目标卷成功即失败；只把“不存在”作为可创建条件，其他 Docker 错误也失败。
- 所有验证完成前禁止 `docker volume create`。

**Step 3: Implement restore without cleanup or startup**

按固定顺序逐个创建 local 卷，再执行：

```bash
docker run --rm --network none --entrypoint /bin/sh \
  -v "${PROJECT_NAME}_${logicalName}:/target" \
  -v "${archivePath}:/restore/source.tar:ro" \
  "${helperImage}" -c 'cd /target && tar -xpf /restore/source.tar'
```

脚本捕获已创建卷名；失败时打印并保留，不调用 `volume rm`。成功输出 backupId、目标卷清单和后续人工启动/验证提示。

**Step 4: Run restore tests GREEN**

```bash
bash deploy/tests/standalone-restore.sh
bash deploy/tests/standalone-backup.sh
bash -n deploy/scripts/restore-standalone.sh
```

Expected: PASS，恢复只写新卷且不启动/删除服务。

**Step 5: Commit**

```bash
git add deploy/scripts/restore-standalone.sh deploy/tests/standalone-restore.sh
git commit -m "feat(deploy): 安全恢复单机同版本事实卷"
```

### Task 5：纳入离线发行和仓库门禁

**Files:**
- Modify: `deploy/tests/offline-bundle.sh`
- Modify: `scripts/verify.sh`
- Modify: `deploy/README.md`

**Step 1: Extend failing bundle assertions**

先修改 `deploy/tests/offline-bundle.sh`，断言解包后以下文件存在且可执行：

```text
deploy/scripts/backup-standalone.sh
deploy/scripts/restore-standalone.sh
deploy/scripts/lib/standalone-recovery-common.sh
deploy/tests/standalone-backup.sh
deploy/tests/standalone-restore.sh
```

同时断言 `manifests/checksums.sha256` 覆盖这些文件。临时 fixture 还没有恢复公共库和测试文件，先运行观察 RED，再补齐 fixture。

**Step 2: Verify RED**

Run: `bash deploy/tests/offline-bundle.sh`

Expected: FAIL，离线 fixture 缺少恢复公共库或测试文件。

**Step 3: Update fixture and verify bundle GREEN**

`build-offline-bundle.sh` 已递归复制 `deploy/scripts` 和 `deploy/tests`，不增加第二套打包逻辑。只补 fixture、可执行权限和断言。

Run: `bash deploy/tests/offline-bundle.sh`

Expected: PASS，恢复能力随离线包发布且受总校验和保护。

**Step 4: Add recovery tests to main verification**

在 `scripts/verify.sh` 第 3 阶段 Compose 校验后运行：

```bash
bash "${ROOT_DIR}/deploy/tests/standalone-backup.sh"
bash "${ROOT_DIR}/deploy/tests/standalone-restore.sh"
```

更新 `deploy/README.md` 构建制品说明，明确恢复脚本随包交付但不包含现场备份或 secret。

**Step 5: Verify deployment gates**

```bash
bash deploy/tests/standalone-backup.sh
bash deploy/tests/standalone-restore.sh
bash deploy/tests/offline-bundle.sh
bash deploy/tests/compose-config.sh
find scripts deploy/scripts deploy/tests -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
```

Expected: 全部 PASS。

**Step 6: Commit**

```bash
git add deploy/tests/offline-bundle.sh scripts/verify.sh deploy/README.md
git commit -m "chore(deploy): 纳入备份恢复发行门禁"
```

### Task 6：编写运行手册与自动化验收报告

**Files:**
- Create: `docs/runbooks/standalone-backup-restore.md`
- Create: `docs/reports/phase-4-standalone-recovery-acceptance.md`
- Modify: `docs/09-deployment-operations.md`
- Modify: `docs/10-testing-quality-gates.md`
- Modify: `docs/12-roadmap-and-codex-workflow.md`
- Modify: `docs/README.md`
- Modify: `deploy/README.md`

**Step 1: Write the operations runbook**

必须覆盖：

- 四个事实卷与明确排除卷。
- 停写审批、`docker compose stop` 后使用 `ps --all`/`ps --status running` 核查。
- 加密目的地确认、备份命令、外部 checksum、异地复制和保留策略。
- 恢复前 secret/config 独立准备；新项目/新卷恢复，不覆盖旧卷。
- 恢复命令、版本/镜像门禁、启动顺序、Flyway/数据库扩展/RabbitMQ/RustFS/API/场景/实时对账。
- 中途失败卷保留和人工清理边界。
- 禁止在线卷 tar、跳过 checksum、跨 major、`down -v`、覆盖卷、把备份放 Web/Git、自动删除旧卷。
- 明确冷备不满足 WAL/PITR 和现有 RPO 目标。

**Step 2: Write acceptance report**

状态必须分为：

```text
AUTOMATED PASS
PRODUCTION NOT RELEASED
```

报告记录精确测试用例数量和命令。以下全部保持 `DEFERRED`：真实数据量、加密介质、跨主机 UID/权限、磁盘损坏、真实 Docker 卷恢复、RPO/RTO、WAL/PITR、共享 S3、五节点、跨版本、季度演练和 24/72 小时长稳。

**Step 3: Update architecture, quality gates and roadmap**

- `docs/09` 将“同版本冷恢复集”标为已实现自动化底座，持续 WAL/基础备份仍未实现。
- `docs/10` 增加 backup manifest/checksum/版本/镜像/拒绝覆盖测试矩阵。
- `docs/12` Phase 4 状态新增“Standalone 同版本冷备恢复：自动化通过”，下一切片指向升级编排或持续备份。
- `docs/README.md` 链接报告和手册，禁止写“备份恢复已经生产通过”。

**Step 4: Validate docs**

Run: `git diff --check`

并以只读脚本校验所有新增相对 Markdown 链接存在。

**Step 5: Commit**

```bash
git add docs deploy/README.md
git commit -m "docs(deploy): 完成单机恢复自动化验收"
```

### Task 7：执行完整验收与最终审查

**Files:**
- Modify only if gates/review find defects.

**Step 1: Run focused recovery gates**

```bash
bash deploy/tests/standalone-backup.sh
bash deploy/tests/standalone-restore.sh
bash deploy/tests/offline-bundle.sh
bash deploy/tests/compose-config.sh
find scripts deploy/scripts deploy/tests -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
```

Expected: 全部 PASS。

**Step 2: Run repository gates proportionate to deployment-only changes**

```bash
./mvnw -B -ntp test
pnpm --dir mine-web check:ui-governance
pnpm --dir mine-web typecheck
pnpm --dir mine-web lint:check
pnpm --dir mine-web test:unit
pnpm --dir mine-web build
bash deploy/tests/prometheus-rules.sh
```

Expected: 全部 PASS。没有数据库/Java/前端生产代码变化时不重复执行容器集成和页面 E2E；若实施中触及这些范围，补跑对应 `verify`/E2E。

**Step 3: Request final review**

使用 `@requesting-code-review` 核查：

- 源卷只读与全服务停机门禁。
- manifest 不泄露 secret、四卷集合准确。
- checksum、tar 路径、版本和镜像 ID 验证都发生在创建卷前。
- 目标卷存在即拒绝，失败不自动删除，成功不自动启动。
- 无 `eval`、无参数注入、无真实卷副作用。
- 自动化与真实恢复延期声明准确。

Critical/Important 全部修复后重新运行 focused gates。

**Step 4: Final diff and status**

```bash
git diff --check
git status --short
git log --oneline -10
```

Expected: 工作树干净，所有计划提交存在。

---

Plan complete and saved to `docs/plans/2026-07-20-standalone-backup-restore-implementation.md`。用户已经要求按推荐路线持续推进，默认在当前专用功能分支使用 `@executing-plans` 分批实施；真实现场恢复与 RPO/RTO 仍按用户决策暂缓。
