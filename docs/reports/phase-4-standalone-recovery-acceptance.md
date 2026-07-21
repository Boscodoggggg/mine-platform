# Phase 4 Standalone 同版本冷备恢复自动化验收报告

## 1. 结论

- 验收日期：2026-07-21
- 自动化结论：`AUTOMATED PASS`
- 生产结论：`PRODUCTION NOT RELEASED`
- 验收范围：单机 Compose 四个事实卷的同发行版本、同镜像 ID 冷备份与恢复，覆盖外部/内部校验和、manifest、tar 路径、全服务停机、源卷只读、目标卷拒绝覆盖、离线发行和仓库门禁。

该结论证明仓库内脚本在 fake Docker 隔离环境中的安全契约可复现，可随离线包交付并用于受控恢复演练。它不证明任何真实矿区数据、真实 Docker 卷、加密介质、跨主机环境或现行 RPO/RTO 已经通过生产验收。

## 2. 已验收能力

| 范围 | 自动化证据 | 结论 |
|---|---|---|
| 冷备停机门禁 | Compose 任一服务运行即拒绝，不调用归档容器 | 通过 |
| 固定事实集合 | 仅归档 PostgreSQL、TimescaleDB、RabbitMQ、RustFS 四个 `local` 卷 | 通过 |
| 源卷安全 | 源卷只读挂载，helper 容器固定 `--network none` | 通过 |
| 可追溯 manifest | 记录 schema、backupId、UTC 时间、发行版本、源项目、Compose 摘要、镜像引用/ID 和固定卷清单 | 通过 |
| 完整性 | 外部 SHA-256 保护最终 tar，内部 SHA-256 精确保护 manifest 和四个卷归档 | 通过 |
| 归档路径安全 | 创建卷前拒绝绝对路径、空路径段、`.`/`..`、第二个顶层目录、链接和特殊文件条目 | 通过 |
| 同版本门禁 | 确认 ID、发行版本、目标镜像引用和本地镜像 ID 必须全部精确匹配 | 通过 |
| 拒绝覆盖 | 任一目标卷已存在即在创建卷前失败；Docker 查询异常不视为“不存在” | 通过 |
| 失败保留 | 恢复失败时保留已创建的新卷并打印清单，不自动删除 | 通过 |
| 人工切换 | 恢复成功不启动服务，不调用健康检查，要求另行准备匹配的配置和 secret | 通过 |
| 离线交付 | 脚本、公共库和两组契约测试进入离线包总校验和 | 通过 |

## 3. 自动化执行证据

当前切片执行：

```bash
bash deploy/tests/standalone-backup.sh
bash deploy/tests/standalone-restore.sh
bash deploy/tests/offline-bundle.sh
bash deploy/tests/compose-config.sh
find scripts deploy/scripts deploy/tests -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n
./mvnw -B -ntp test
pnpm --dir mine-web check:ui-governance
pnpm --dir mine-web typecheck
pnpm --dir mine-web lint:check
pnpm --dir mine-web test:unit
pnpm --dir mine-web build
bash deploy/tests/prometheus-rules.sh
```

最终执行结果：

- Standalone 冷备份安全契约 7 项通过。
- Standalone 恢复先验证、拒绝覆盖和不自动启动契约 19 项通过；额外覆盖校验文件无效行、外层 tar 链接/特殊文件和 Docker 卷清单查询失败。
- 离线包资产、可执行权限和总 SHA-256 清单校验通过。
- Standalone Compose 配置契约通过。
- `scripts/`、`deploy/scripts/` 和 `deploy/tests/` 下全部 Shell 脚本语法检查通过。
- Maven 12 个 Reactor 模块全部成功；本次重新生成的 Surefire 报告共 1052 项测试通过，失败、错误和跳过均为 0。
- 前端 UI 治理 49 项通过，类型检查通过；静态检查覆盖 408 个文件、141 条规则，警告和错误均为 0。
- Vitest 70 个测试文件共 437 项通过；生产构建通过。
- Prometheus 共 54 条规则通过语法和行为检查。

测试数量来自本次最终生命周期生成的 Surefire/Vitest 输出，不复用旧阶段数字。当前切片没有修改数据库、Java 或业务前端生产代码，因此按计划未重复执行容器集成和页面 E2E；真实恢复继续保持延期。

## 4. 关键不变量

1. 四个事实卷必须处于全部服务停止后的同一冷恢复点；Redis、监控、暂存和缓存不冒充事实备份。
2. 环境文件、secret、TLS 材料、镜像和离线发行包独立托管，不进入数据归档。
3. 外部/内部校验和、tar 路径、manifest、版本、镜像和全部目标卷预检完成前，不得创建任何卷。
4. 源卷始终只读，归档和恢复 helper 容器始终无网络；所有用户参数通过 Bash 数组传递，不使用 `eval`。
5. 目标卷必须全部不存在；恢复脚本不得停止/启动 Compose，不得覆盖、清空或删除任何卷。
6. 失败创建的新卷必须留给人工取证和批准后的清理，旧卷在观察期结束前保持不变。
7. 冷备恢复自动化不代表持续 WAL/PITR、高可用、RPO/RTO 或生产恢复已经达标。

详细停写、备份、异地复制、恢复、启动和对账步骤见 [Standalone 同版本冷备份与恢复运行手册](../runbooks/standalone-backup-restore.md)。

## 5. 明确暂缓的真实验证

| 生产门禁 | 状态 | 后续证据 |
|---|---|---|
| 真实数据量 | `DEFERRED` | 客户数据规模、归档大小、耗时、空间余量和一致性抽样 |
| 加密介质 | `DEFERRED` | 静态加密、密钥托管、访问审计、异地复制和介质销毁记录 |
| 跨主机 UID/权限 | `DEFERRED` | 目标 Docker/文件系统版本、UID/GID、权限和 SELinux/AppArmor 恢复报告 |
| 磁盘损坏 | `DEFERRED` | 截断、坏块、空间耗尽、读取错误和取证流程演练 |
| 真实 Docker 卷恢复 | `DEFERRED` | 四个生产形态卷完整恢复、应用启动与逐项数据对账记录 |
| RPO/RTO | `DEFERRED` | 停写时间、备份窗口、恢复耗时、实际丢失窗口和 SLA 结论 |
| WAL/PITR | `DEFERRED` | PostgreSQL/TimescaleDB 持续 WAL、基础备份和时间点恢复演练 |
| 共享 S3 | `DEFERRED` | 正式对象存储版本、对象锁、加密、清单和同恢复点验证 |
| 五节点 | `DEFERRED` | 数据库主备、RabbitMQ quorum、共享对象存储和故障切换恢复 |
| 跨版本 | `DEFERRED` | PostgreSQL/TimescaleDB major、应用 migration、升级和回滚演练 |
| 季度恢复演练 | `DEFERRED` | 责任人、审批、完整恢复、对账、切换和复盘审计 |
| 24/72 小时长稳 | `DEFERRED` | 恢复后持续写入、查询、实时链路、资源和错误率趋势 |

以上项目不阻止继续产品化开发，但全部阻止“备份恢复已经生产通过”或“已满足商业 SLA”的对外声明。完成现场证据前，状态保持 `PRODUCTION NOT RELEASED`。
