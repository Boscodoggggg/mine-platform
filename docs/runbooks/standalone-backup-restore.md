# Standalone 同版本冷备份与恢复运行手册

## 1. 适用范围与结论边界

本手册适用于单机 Compose 部署的同发行版本、同镜像 ID 冷备份与恢复。操作窗口内必须停止全部平台服务，恢复目标必须使用新的 Compose 项目名和不存在的新卷。脚本不会停止或启动服务，不会覆盖、删除任何已有卷，也不会打包 `deploy/.env`、证书或 TLS 私钥。

冷备份只覆盖四个事实卷：

| 逻辑卷 | 主要事实 | 默认实际卷名 |
|---|---|---|
| `postgres_data` | 管理库、配置、业务事实、审计和 Flyway 历史 | `${PROJECT_NAME}_postgres_data` |
| `timescale_data` | 遥测、位置和聚合时序事实 | `${PROJECT_NAME}_timescale_data` |
| `rabbitmq_data` | Broker 定义、账号状态、持久队列和窗口内消息 | `${PROJECT_NAME}_rabbitmq_data` |
| `rustfs_data` | 场景资源和其他对象事实 | `${PROJECT_NAME}_rustfs_data` |

以下内容明确不进入归档：

- Redis、Prometheus、Grafana、Loki 和 Alloy 数据卷。
- 场景上传暂存、Gateway 本地缓存及其他可重建临时目录。
- `deploy/.env`、`deploy/certs/` 中的 TLS 证书和私钥。
- 应用 JAR、前端 dist、基础镜像和离线安装包；它们必须由发行制品库按摘要独立保管。

这套能力用于建立可校验、默认不覆盖的灾难恢复底座，不等于持续 WAL 归档、PITR 或热备。全服务停机至归档完成之间发生的写入不可被捕获，因此当前冷备本身不满足 [部署与运维设计](../09-deployment-operations.md) 中既定的生产 RPO 目标。

## 2. 角色、审批和恢复点材料

每次操作至少由执行人与复核人共同完成，并在变更单记录矿区、源项目名、发行版本、镜像 ID、停写开始时间、`backupId`、归档 SHA-256、加密介质、异地副本位置和恢复结果。

开始前必须具备：

1. 已批准的停写和维护窗口，设备上行、命令下发、策略发布、文件上传及后台任务均已停止。
2. 与运行实例匹配的不可变离线发行包和四项基础服务镜像。
3. 主机外、访问受控且静态加密的目标目录；可移动介质还需确认传输与保管责任人。
4. 与恢复点匹配、独立托管的 `deploy/.env`、TLS 材料及其版本记录。不得从备份归档中补造凭据。
5. 足够的源卷读取空间、临时空间和备份目标空间，且维护窗口允许完整停止平台。

## 3. 创建冷备份

以下示例均在发行包根目录执行。先固定参数，禁止把生产备份写入 Web 根目录、Git 工作树或未加密的普通用户目录：

```bash
ENV_FILE=deploy/.env
SOURCE_PROJECT=mine-standalone
RELEASE_VERSION=<release-version>
BACKUP_DIR=/mnt/encrypted-mine-backup
COMPOSE_FILE=deploy/docker-compose.yml
```

### 3.1 停写、停止服务并复核

获得停写批准并确认设备侧已缓冲或停止上报后，执行：

```bash
docker compose -p "${SOURCE_PROJECT}" \
  --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" stop

docker compose -p "${SOURCE_PROJECT}" \
  --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps --all

docker compose -p "${SOURCE_PROJECT}" \
  --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" \
  ps --status running --services
```

最后一条命令必须没有输出。任何服务仍为运行状态都必须停止操作并查明原因；不得在在线卷上直接执行 `tar`，也不得用 `down -v` 代替停止服务。

### 3.2 生成并校验归档

复核加密目标目录后执行：

```bash
deploy/scripts/backup-standalone.sh \
  --env-file "${ENV_FILE}" \
  --project-name "${SOURCE_PROJECT}" \
  --release-version "${RELEASE_VERSION}" \
  --output-dir "${BACKUP_DIR}" \
  --confirm-secure-destination
```

脚本先确认所有服务停止、四个源卷存在且为 `local` 驱动，再使用无网络容器只读挂载源卷。成功后会生成：

```text
mine-standalone-backup-<releaseVersion>-<backupId>.tar
mine-standalone-backup-<releaseVersion>-<backupId>.tar.sha256
```

把脚本输出中的准确路径赋给 `ARCHIVE`，在同一挂载点重新验证外部校验和：

```bash
ARCHIVE=/mnt/encrypted-mine-backup/mine-standalone-backup-<releaseVersion>-<backupId>.tar
(
  cd "$(dirname "${ARCHIVE}")"
  sha256sum -c "$(basename "${ARCHIVE}").sha256"
)
```

不允许跳过外部或归档内校验和。归档只在最终文件和校验文件都完成后才视为发布；同名文件已存在时脚本会拒绝覆盖。

### 3.3 异地复制和保留

1. 将归档与 `.sha256` 作为一组复制到现场 NAS 和加密离线或异地介质。
2. 在目标介质重新执行 SHA-256 校验，记录介质标识、复制时间、责任人和结果。
3. 默认保留 7 个日备份、4 个周备份和 12 个按月备份；客户合同或法规要求更长时取更严格值。
4. 只有新备份完成、两处副本校验通过且过期审批完成后，才允许人工删除超期备份。脚本不得自动清理历史归档。
5. 平台保持停止，直到变更负责人决定恢复原项目或进入隔离恢复；备份脚本不会自动启动服务。

## 4. 恢复到新项目和新卷

### 4.1 恢复前检查

恢复前必须重新核对：

- 归档和 `.sha256` 同时存在，`backupId`、发行版本及 SHA-256 与变更单一致。
- 当前发行根目录和四项本地镜像与 manifest 记录一致；不得跨 PostgreSQL/TimescaleDB major 或跨发行版本恢复。
- 使用新的目标项目名，例如 `mine-restore-20260721`。四个 `${TARGET_PROJECT}_<logicalName>` 目标卷必须全部不存在。
- 目标项目没有任何运行服务；同机恢复时源项目也保持停止，避免错误连接和双写。
- 已从独立安全存储准备与恢复点匹配的 `deploy/.env` 和 TLS 材料，并限制文件权限。备份归档不包含这些材料。

先只读核查目标状态：

```bash
TARGET_PROJECT=mine-restore-20260721
RESTORE_ENV=/secure/config/standalone.env
COMPOSE_FILE=deploy/docker-compose.yml

docker compose -p "${TARGET_PROJECT}" \
  --env-file "${RESTORE_ENV}" -f "${COMPOSE_FILE}" ps --all

docker volume ls --format '{{.Name}}' | grep "^${TARGET_PROJECT}_" || true
```

若发现任何同名目标卷，不得重命名后继续碰运气，也不得清空或覆盖；改用另一个经批准的新项目名。

### 4.2 执行恢复

```bash
BACKUP_ID=<backupId>
EXPECTED_RELEASE_VERSION=<release-version>

deploy/scripts/restore-standalone.sh \
  --archive "${ARCHIVE}" \
  --env-file "${RESTORE_ENV}" \
  --project-name "${TARGET_PROJECT}" \
  --expected-release-version "${EXPECTED_RELEASE_VERSION}" \
  --confirm-backup-id "${BACKUP_ID}"
```

脚本在创建任何卷前依次验证外部 SHA-256、tar 路径与条目类型、内部 SHA-256、manifest、确认 ID、发行版本、镜像引用和本地镜像 ID，并再次确认目标服务停止、Docker 卷清单可读取且四个目标卷全部不存在。校验文件夹杂无效行，或外层 tar 含链接/特殊文件时均会拒绝恢复。成功只创建并恢复四个 `local` 卷，不启动 Compose。

如果恢复中途失败，脚本会打印已创建的新卷并保留现场，不会自动删除。执行人必须冻结这些卷、保存日志并提交故障分析；只有确认卷属于本次失败恢复、无需取证且两人批准后，才可人工逐一执行 `docker volume rm`。旧源卷和上一份有效备份在观察期结束前不得删除。

## 5. 分阶段启动与数据对账

恢复成功后，先启动事实服务和 Redis，再启动应用与入口：

```bash
docker compose -p "${TARGET_PROJECT}" \
  --env-file "${RESTORE_ENV}" -f "${COMPOSE_FILE}" \
  up -d postgres timescale rabbitmq rustfs redis

docker compose -p "${TARGET_PROJECT}" \
  --env-file "${RESTORE_ENV}" -f "${COMPOSE_FILE}" ps

docker compose -p "${TARGET_PROJECT}" \
  --env-file "${RESTORE_ENV}" -f "${COMPOSE_FILE}" \
  up -d timescale-reader-bootstrap mine-server mine-gateway mine-telemetry nginx

MINE_COMPOSE_PROJECT_NAME="${TARGET_PROJECT}" \
MINE_ENV_FILE="${RESTORE_ENV}" \
  bash deploy/scripts/health-check.sh
```

正式切换前至少完成以下核查并保存输出：

| 核查项 | 最低证据 |
|---|---|
| Flyway | `mine-server` 日志无 migration 失败；管理库 Flyway 历史版本与恢复点/发行包一致 |
| PostgreSQL | 容器健康；组织、矿区、设备、告警、作业、审计及关键表行数与备份基线一致 |
| TimescaleDB | 容器健康；`timescaledb` 扩展版本和 `timescaledb.license=apache` 正确；hypertable、索引、最早/最晚时间和关键设备抽样一致 |
| RabbitMQ | 节点健康；预期 vhost、交换机、quorum 队列、绑定和消费者恢复，无无法解释的持续积压 |
| RustFS | 服务健康；bucket、对象数量/容量、抽样对象 SHA-256 与场景资源清单一致 |
| API 与权限 | 管理员和受限角色均可登录；健康接口、菜单、矿区数据范围和关键查询符合预期 |
| 三维场景 | 默认发布场景、资源清单、GLB/3D Tiles Range 读取和二维降级均可用，管理库与对象事实来自同一恢复点 |
| 实时链路 | 在批准的模拟数据下验证 RabbitMQ → Gateway/Telemetry → TimescaleDB/Redis → WebSocket；断言无重复业务副作用和无法解释的序号缺口 |

只有对账通过并完成双人签字后，才允许把设备和用户流量切换到目标项目。对账失败时停止目标项目但保留全部卷与日志，回到旧环境或新的隔离恢复点；不得修改已恢复卷来“修平”差异。

## 6. 禁止操作

- 禁止对运行中的事实卷直接 `tar`，或在未批准停写时创建所谓一致性备份。
- 禁止跳过、手工改写外部/内部 SHA-256、manifest、版本或镜像 ID 门禁。
- 禁止跨发行版本、跨 PostgreSQL/TimescaleDB major 直接挂卷或恢复。
- 禁止在生产环境执行 `docker compose down -v`。
- 禁止覆盖、清空、复用已有目标卷；禁止脚本或值班任务自动删除旧卷、失败卷和历史备份。
- 禁止把归档、`deploy/.env`、证书或私钥放入 Web 可访问目录、Git、工单附件或普通聊天工具。
- 禁止把这套冷备当作 WAL/PITR、连续备份、高可用或既定 RPO/RTO 已达标的证据。

## 7. 当前未覆盖的生产门禁

真实 Docker 卷与目标数据量恢复、跨主机 UID/GID 和权限、加密介质、磁盘损坏、共享 S3、五节点切换、跨版本升级、WAL/PITR、RPO/RTO、季度恢复演练以及 24/72 小时长稳仍需现场证据。自动化结论见 [Phase 4 Standalone 恢复自动化验收报告](../reports/phase-4-standalone-recovery-acceptance.md)。
