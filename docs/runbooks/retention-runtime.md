# 数据保留运行手册

## 1. 适用范围与红线

本手册用于 `RETENTION` 策略的影响预览、发布观察、Telemetry 有界清理、告警处置、暂停和恢复。首版只治理 `telemetry_numeric` 原始数值遥测和 `telemetry_quality_evidence` 质量证据。

以下事实不允许由本任务删除：遥测 Inbox、持久最新值、设备 boot/水位、告警运行状态与 Outbox、策略镜像与运行修订、服务端告警、命令、操作审计和位置轨迹。所有 SQL 检查必须使用只读运维账号；禁止手工无条件 `DELETE`、直接修改 `telemetry_retention_policy`、覆盖运行修订或删除 `telemetry_retention_execution`。

保留删除不可逆。策略暂停或回滚只能阻止后续删除，不能恢复已删数据；恢复必须依赖删除前、经过验证的数据库备份。未完成真实数据 dry-run、备份恢复、容量/WAL、HA 和长稳演练时，本功能不得取得矿区生产放行签字。

## 2. 发布前检查

1. 记录变更工单、责任人、矿区、产品/设备范围、法规或合同依据、期望期限和回滚决策人。
2. 确认 TimescaleDB 最近一次成功备份、恢复点、可用空间和恢复演练证据；没有可验证恢复点时停止发布。
3. 确认 Telemetry V15/V16、管理服务 V27/V28/V29 已迁移，TimescaleDB 只读账号可查询保留镜像、执行事实和两个受控数据集。
4. 检查当前执行状态、最近成功时间、连续失败/`PARTIAL`、WAL、磁盘、锁等待和数据库慢查询；异常未关闭时不得扩大清理范围。
5. 优先使用产品级安全期限，设备组/设备只配置确有合同或容量依据的例外；同优先级组冲突必须在控制面阻止。
6. 在“数据治理 → 数据保留”检查最终值和来源。生产首发不得直接照搬演示预设，必须按现场法规、合同、备份周期和查询需求确认。

## 3. 影响预览与审批

先保存目标策略草稿，再执行影响预览；未保存修改不得用于预览或发布。普通发布预览保存草稿，历史回滚必须针对历史版本和原作用域重新预览。页面必须展示：

- 统计时点、目标设备数、最终值变化设备数和发布后仍启用删除的设备数；
- 原始遥测、质量证据各自的候选行数和最早候选时间；
- 是否出现 `atLeast=true`。该标记表示达到 100 万行统计上限，实际候选更多；
- `enabled`、保留等级和两个期限。

预览使用只读可重复读快照，不会执行删除，并签发 15 分钟一次性凭证。激活时服务端会重新校验管理员、保存版本内容、作用域、成员、组优先级和最终解析哈希；凭证过期、复用或状态变化必须重新预览。预览后新增老化候选会增加、并发保留执行会减少候选，因此它只是数据库时点估算，既非上下限也非删除承诺。纯停删且所有最终策略均禁用时不依赖 TimescaleDB；存在更具体启用覆盖时仍必须统计。出现以下任一情况必须停止：作用范围异常、仍启用设备异常、期限短于合同/法规、质量证据早于原始数据、统计封顶但没有容量评估、最早时间超出预期、数据库查询不可用或备份状态不明。

设备组成员、优先级和归档是跨策略类型的公共配置。只要设备组已有当前 `RETENTION` 发布，这三类变更即使从在线状态、数据质量或告警策略页面发起，也必须先预览拟变更后的逐设备保留影响，核对后再次提交一次性凭证。最终事务同时重检 `device:group:edit` 和当前 `device:strategy:publish` 权限；权限被撤销、设备组版本变化、成员差异变化、凭证过期或复用时必须重新预览。没有当前保留发布、成员没有实际差异或优先级没有变化时，服务端直接执行普通设备组流程，不伪造删除影响。

## 4. 发布和运行确认

1. 填写可审计原因并二次确认，确认一次性预览凭证尚未过期；发布/回滚重试必须复用同一个 `Idempotency-Key`。
2. 确认控制面创建更高运行修订，Telemetry 成功原子加载 `RETENTION` 最后正确镜像。
3. 查询当前镜像，确认设备数、修订和期限符合审批：

~~~sql
SELECT product_id, runtime_revision, snapshot_id, enabled, retention_class,
       raw_telemetry_days, quality_evidence_days, count(*) AS device_count,
       max(updated_at) AS last_applied_at
FROM telemetry_retention_policy
GROUP BY product_id, runtime_revision, snapshot_id, enabled, retention_class,
         raw_telemetry_days, quality_evidence_days
ORDER BY runtime_revision DESC, product_id;
~~~

4. 查询最近执行事实，不得用修改数据库的方式“修正”状态：

~~~sql
SELECT execution_id, instance_id, status, policy_count,
       raw_rows_deleted, quality_rows_deleted, batch_count,
       started_at, completed_at, last_error
FROM telemetry_retention_execution
ORDER BY started_at DESC, execution_id DESC
LIMIT 50;
~~~

5. `SUCCEEDED` 表示本轮已扫描完当前候选；`PARTIAL` 表示达到有界上限、下轮继续；`FAILED` 必须保留错误证据并处置；锁忙的实例不写执行事实。

## 5. 指标与告警

基础观察：

~~~promql
sum by (status) (increase(mine_telemetry_retention_runs_total[30m]))
sum by (dataset) (increase(mine_telemetry_retention_rows_deleted_total[30m]))
max by (job) (mine_telemetry_retention_running)
max by (job) (mine_telemetry_retention_policy_count)
max by (job) (mine_telemetry_retention_consecutive_failures)
max by (job) (mine_telemetry_retention_consecutive_partial)
time() - max by (job) (mine_telemetry_retention_last_success_epoch_seconds)
~~~

### `MineTelemetryRetentionConsecutiveFailures`

1. 立即检查最近 `FAILED` 事实、TimescaleDB 连接、磁盘/WAL、锁等待和执行计划。
2. 如果仍可能持续错误删除或数据库不稳定，按第 6 节停止调度。
3. 修复后保持原执行事实，先用安全期限重新预览，再观察完整 `SUCCEEDED`。

### `MineTelemetryRetentionNoRecentSuccess`

1. 区分连续失败、始终锁忙、调度未启动和持续 `PARTIAL`。
2. 核对 `MINE_TELEMETRY_RETENTION_ENABLED`、实例启动时间、固定延迟和数据库 advisory lock 持有会话。
3. 不得通过删除候选或清零指标消除告警。

### `MineTelemetryRetentionPersistentlyPartial`

1. 统计每轮删除量、候选增长、WAL/IO 和查询耗时，判断是历史积压还是新数据增长超过处理能力。
2. 优先增加调度频率或分阶段缩短作用范围；调整批次/周期前必须验证数据库负载和备份窗口。
3. 不得直接把参数提升到最大值，也不得改用越过单设备期限的 `drop_chunks`。

## 6. 暂停与安全回退

### 紧急停止全部删除

1. 同时停止或缩容全部正在运行的 Telemetry 旧实例，禁止使用滚动重启；滚动期间旧实例可能依次取得 advisory lock 并继续删除。
2. 确认 `mine_telemetry_retention_running == 0`、旧进程均退出且数据库 advisory lock 已释放，再以 `MINE_TELEMETRY_RETENTION_ENABLED=false` 启动所需 Telemetry 实例。
3. 检查最后一个批次是否已提交。已提交批次不能由事务回滚恢复，未提交批次会自动回滚。
4. 保留执行事实、日志、指标、策略快照、审批记录和数据库恢复点，不要“清理现场”。

### 暂停特定作用域

1. 在对应产品/组/设备发布 `enabled=false` 的完整原子 `policy`，或回滚到已知安全的禁用版本。
2. 等待 Telemetry 加载更高修订，并用只读 SQL 确认目标设备镜像已经禁用。
3. 如果风险不能接受等待分发，先执行全局紧急停止，再发布禁用策略。

### 恢复调度

1. 完成根因、影响范围和已删行评估；需要恢复数据时先执行第 8 节，不得先重启清理。
2. 重新预览安全策略，经审批后发布并确认最后正确镜像。
3. 以保守批次恢复一个 Telemetry 调度实例，观察至少一个完整 `SUCCEEDED`；再恢复冗余实例。

## 7. 常见故障

| 现象 | 判断与动作 |
|---|---|
| 多实例只有一个执行 | 正常；数据库 advisory lock 保证单实例工作 |
| `RUNNING` 长时间不结束 | 检查节点、数据库会话和 `running` 指标；超过失联阈值后由后续实例收敛为 `FAILED` |
| 执行历史为空 | 检查调度是否启用、初始延迟、数据库迁移和 Telemetry 日志；锁忙实例不会产生事实 |
| 预览 `503` | 检查管理服务 TimescaleDB 只读账号、readiness、网络和 V15/V16；不得临时换用写账号 |
| 连续 `PARTIAL` | 按积压、WAL、IO 和查询计划评估，谨慎调整频率/批次，不绕过期限 |
| 发布后镜像未变化 | 检查策略事件、内部快照 API、运行修订、死信和最后正确值保护，禁止直接改镜像表 |
| 怀疑误删 | 立即全局停调度、冻结证据、确定恢复点和影响范围，进入第 8 节 |

## 8. 备份恢复

恢复已删事实必须遵循矿区数据库恢复方案：

1. 停止所有保留调度并记录最后执行标识、完成时间、策略修订和两个数据集删除量。
2. 选择删除前恢复点，在隔离环境完成 TimescaleDB 版本、扩展、hypertable、索引、行数和抽样查询验证。
3. 决定整库切换还是受控回灌。回灌必须保留原主键/事件语义并评估与现有写入的重复冲突，不能用任意导入脚本直接覆盖生产。
4. 由数据库、平台和业务负责人共同批准后执行，记录实际 RPO/RTO 和差异。
5. 恢复完成后先发布禁用/安全策略，再恢复调度。

本仓库的 Testcontainers 自动化没有执行现场备份恢复，不能替代上述流程。

## 9. 升级不变量

- 管理服务 V27 注册 `RETENTION`，V28 发布独立菜单并补齐只读依赖，V29 创建预览激活凭证；升级后复核角色和凭证表注释/约束，不得出现编辑/发布权限扩张。
- Telemetry V15/V16 只向前迁移。应用回退前确认旧版本容忍新表；不得回滚 Flyway 或删除运行水位。
- 配置回滚以更高发布序号和运行修订形成新事实，不修改历史版本。
- 真实数据、备份恢复、HA、容量和 24/72 小时长稳是独立现场门禁，自动化通过不等于生产放行。
