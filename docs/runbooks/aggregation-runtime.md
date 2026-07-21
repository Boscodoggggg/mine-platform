# 数值聚合运行手册

## 1. 适用范围与红线

本手册用于 `AGGREGATION` 策略发布后的 Telemetry 常规物化、冷热历史查询、显式重算、告警处置、暂停和恢复。首版只处理 `telemetry_numeric` 数值遥测，固定输出 `AVG`、`MIN`、`MAX`、样本数、桶内最差质量和最晚平台接收时间。

平台只接受 60、300、900、3600、21600、86400 秒 UTC 对齐桶；单策略最多 100 条属性桶规则，修正窗口为 300～604800 秒且不得小于最大桶宽的两倍。禁止自由 SQL、表达式、用户函数、跨设备公式、字符串/对象聚合、位置轨迹降采样和原地覆盖既有聚合事实。

常规物化只处理已经越过修正窗口的闭合桶；显式重算可以纳入超窗口迟到样本，但必须由具有 `governance:aggregation:execute` 权限的管理员针对单设备、单属性、单桶宽和有界 `[from,to)` 范围发起。未完成真实数据、容量、HA、备份恢复和长稳验证时，本功能不得取得矿区生产放行签字。

## 2. 发布与启用前检查

1. 记录变更工单、责任人、产品/设备范围、属性、桶宽、修正窗口、查询需求和回退决策人。
2. 确认管理服务已迁移到 V30～V32，Telemetry 已迁移到 V17～V20；不得回滚 Flyway 或手工修改策略镜像、执行事实、聚合事实和请求租约。
3. 确认目标属性是数值遥测，桶宽来自固定白名单，修正窗口覆盖现场正常迟到和补传上界。
4. 确认 `MINE_TELEMETRY_AGGREGATION_ENABLED`、实例标识、批次/周期和调度间隔符合环境审批；显式重算还需开启 `MINE_TELEMETRY_AGGREGATION_RECOMPUTE_ENABLED`，并能读取与策略分发共用的外部 service token 文件。
5. 检查 TimescaleDB 可用空间、WAL、锁等待、慢查询、最近聚合成功、持续 `PARTIAL`、超窗口迟到和数据保留覆盖阻塞告警。异常未关闭前不得扩大策略范围。
6. 确认历史查询调用方会在后续页原样回传首页返回的 `watermark` 和 `rollupWatermark`，不能自行换水位拼接同一分页会话。

## 3. 策略发布后确认

在“数据治理 → 数值聚合”或策略工作台发布保存后的确切版本，复核最终有效值和来源。发布、回滚、设备组成员/优先级/归档变化会生成更高运行修订；Telemetry 只有完成共享定义复验、整包完整性校验和本地事务切换后才替换最后正确镜像。

使用只读账号检查当前镜像：

~~~sql
SELECT product_id, runtime_revision, snapshot_id, enabled,
       correction_window_seconds, count(*) AS device_count,
       max(updated_at) AS last_applied_at
FROM telemetry_aggregation_policy
GROUP BY product_id, runtime_revision, snapshot_id, enabled,
         correction_window_seconds
ORDER BY runtime_revision DESC, product_id;
~~~

抽查规则只能读取规范化 JSON，不得直接修改：

~~~sql
SELECT device_id, runtime_revision, content_hash,
       values_json -> 'policy' -> 'rules' AS rules
FROM telemetry_aggregation_policy
WHERE device_id = :device_id;
~~~

加载失败时先检查策略事件、内部快照 API、service token、修订水位和死信。低修订、同修订异内容或损坏快照保留上一份最后正确镜像，禁止通过改表“追平”。

## 4. 常规物化与冷热查询

常规聚合每轮先获取数据库会话级 advisory lock，再固定当前原始摄取 `source_watermark`。执行器只读取该水位以内、已经闭合且越过修正窗口的样本；单批和单轮达到上限时以 `PARTIAL` 结束，下轮重新扫描，不保存脆弱行游标。

查看最近执行事实：

~~~sql
SELECT execution_id, execution_kind, instance_id, status,
       policy_count, rule_count, source_watermark, bucket_count,
       rollup_watermark, late_rows_ignored,
       started_at, completed_at, last_error
FROM telemetry_aggregation_execution
ORDER BY started_at DESC, execution_id DESC
LIMIT 50;
~~~

状态含义：

- `SUCCEEDED`：本轮在固定水位下完成全部可处理桶。
- `PARTIAL`：达到受控批次/周期上限，后续轮次继续。
- `FAILED`：已记录脱敏终态，原始数据保持不变。
- 锁忙：当前实例不创建伪执行事实，由持锁实例继续工作。

历史查询首页固定原始摄取水位和聚合修订水位；冷区从 `telemetry_numeric_rollup` 选择不超过两个水位的最新版本，修正窗口内热区读取原始样本，边界结果按时间稳定合并。后台产生更高聚合版本不会污染已经开始的分页。数据保留只有在成功聚合版本覆盖对应原始行且桶越过修正窗口后才允许删除；覆盖缺失必须 fail closed。

## 5. 显式重算操作

只有出现已确认的超窗口补传、数据修复或策略治理需求时才发起显式重算：

1. 在数值聚合治理页选择当前启用的精确规则，填写单设备、单属性、桶宽、UTC 对齐 `[from,to)` 和可审计原因。
2. 范围最长 31 天且最多 10000 个桶；提交时生成并复用稳定 `Idempotency-Key`。相同操作人的相同键只能重放完全相同的请求。
3. API 返回 `202` 只表示已登记 `PENDING` 控制面事实，不表示重算成功。
4. Telemetry 每轮常规聚合退出后最多认领一个请求；认领使用 `FOR UPDATE SKIP LOCKED` 和 1 分钟～1 小时租约，每次租约更换 claim token，但请求和执行标识保持稳定。
5. Telemetry 再次校验当前最后正确镜像中存在完全匹配的启用规则，固定原始摄取水位，纳入该水位内全部样本，包括超出普通修正窗口的迟到样本，并只追加更高 `rollup_revision`。
6. 完成回写必须匹配当前 claim token。旧租约的迟到回写返回租约丢失；后续持有者用同一 `execution_id` 重放终态，不重复生成另一套事实。

控制面检查：

~~~sql
SELECT request_id, status, device_id, property_id, bucket_seconds,
       from_at, to_at, requested_by, execution_id,
       claimed_by, claimed_at, lease_until, attempt_count,
       created_at, updated_at, completed_at, last_error
FROM dev_aggregation_recompute_request
ORDER BY created_at DESC, request_id DESC
LIMIT 50;
~~~

Telemetry 对账：

~~~sql
SELECT execution_id, recompute_request_id, status, source_watermark,
       target_device_id, target_property_id, target_bucket_seconds,
       requested_from, requested_to, runtime_revision, snapshot_id,
       policy_content_hash, bucket_count, rollup_watermark, last_error
FROM telemetry_aggregation_execution
WHERE execution_kind = 'RECOMPUTE'
ORDER BY started_at DESC, execution_id DESC
LIMIT 50;
~~~

禁止手工把 `PENDING/RUNNING` 改为终态、续租、替换 claim token 或删除请求。卡死和失联依赖租约到期后的稳定重放恢复。

## 6. 指标与告警

基础观察：

~~~promql
sum by (status) (increase(mine_telemetry_aggregation_runs_total[30m]))
sum(increase(mine_telemetry_aggregation_buckets_written_total[30m]))
max by (job) (mine_telemetry_aggregation_consecutive_failures)
max by (job) (mine_telemetry_aggregation_consecutive_partial)
time() - max by (job) (mine_telemetry_aggregation_last_success_epoch_seconds)
sum by (job) (increase(mine_telemetry_aggregation_late_rows_ignored_total[30m]))
sum by (status) (increase(mine_telemetry_aggregation_recompute_runs_total[30m]))
max by (job) (mine_telemetry_aggregation_recompute_consecutive_failures)
max by (job) (mine_server_aggregation_recompute_unfinished)
max by (job) (mine_server_aggregation_recompute_claimable_oldest_age_seconds)
~~~

### 常规物化告警

- `MineTelemetryAggregationConsecutiveFailures`：检查最近 `FAILED` 事实、TimescaleDB、磁盘/WAL、锁和脱敏错误；不得通过删事实或清零指标消警。
- `MineTelemetryAggregationNoRecentSuccess`：区分调度未启用、始终锁忙、连续失败和持续 `PARTIAL`。
- `MineTelemetryAggregationPersistentlyPartial`：评估桶积压、查询计划和单轮产量，先调整频率与作用范围，再经容量验证调整批次。
- `MineTelemetryAggregationLateRowsIgnored`：核对设备时间、边缘缓存、网络恢复和修正窗口；确需修复历史时走显式重算，不得直接改 rollup。
- `MineTelemetryRetentionAggregationCoverageBlocked`：说明保留候选尚无成功聚合覆盖；先恢复聚合，禁止绕过覆盖检查删除原始数据。

### 显式重算告警

- `MineTelemetryAggregationRecomputeConsecutiveFailures`：检查内部控制面连通性、service token、当前策略精确匹配和 TimescaleDB 错误。
- `MineTelemetryAggregationRecomputeWorkerStuck`：检查当前 SQL、advisory lock、线程转储和租约时长；不要手工终结租约。
- `MineTelemetryAggregationRecomputeNoRecentPoll`：检查调度线程、mine-server 健康、内部网络和 token 文件读取权限。
- `MineServerAggregationRecomputeClaimableBacklog`：检查是否启用重算消费、最老请求及过期租约；积压处理仍保持每轮一个请求的限速边界。
- `MineTelemetryAggregationRecomputeLeaseLost`：偶发时由稳定执行标识自动恢复；持续发生应检查执行时长是否经常超过租约及实例时钟/网络。

全部指标只使用固定状态等低基数标签，禁止把设备、属性、请求、执行标识或错误文本加入标签。

## 7. 暂停与安全回退

### 暂停常规聚合和重算

1. 同时停止或缩容全部旧 Telemetry 实例，等待 `mine_telemetry_aggregation_running == 0` 和 `mine_telemetry_aggregation_recompute_running == 0`，确认数据库 advisory lock 已释放。
2. 以 `MINE_TELEMETRY_AGGREGATION_ENABLED=false` 启动所需实例会同时停止常规聚合和重算消费。只暂停重算时保留聚合开启并设置 `MINE_TELEMETRY_AGGREGATION_RECOMPUTE_ENABLED=false`。
3. 保留策略镜像、请求、执行事实、聚合版本、日志和指标，不删除或篡改证据。
4. 已提交的新聚合版本无需回滚：已开始的查询仍由原 `rollupWatermark` 固定，新的首页才会看到更高版本。

### 暂停特定作用域

发布 `enabled=false` 的完整 `AGGREGATION.policy`，或回滚到已知安全版本；等待更高运行修订并用只读 SQL 确认镜像。停用聚合会使有对应规则但缺少覆盖的原始数据保留继续 fail closed，这是安全行为。

### 恢复

完成根因和影响范围评估后，先确认最后正确镜像及未完成请求，再以保守批次恢复一个 Telemetry 实例。至少观察一次完整 `SUCCEEDED`、重算轮询和冷热查询对账，之后再恢复冗余实例。

## 8. 升级、备份与现场门禁

- 管理服务 V30 开放聚合策略，V31 创建治理菜单和重算请求，V32 增加可恢复租约；Telemetry V17 创建策略镜像，V18 创建版本化事实，V19 补充聚合源查询索引，V20 标识显式重算执行。
- 迁移只向前。应用回退前验证旧版本容忍新表和新增列；策略回退通过更高发布事实完成。
- 备份必须覆盖管理 PostgreSQL 的重算请求和 TimescaleDB 的策略镜像、执行事实、rollup 与原始数据。恢复演练需验证两个数据库的关联执行标识和水位一致性。
- 当前自动化没有连接真实设备、矿区网络或生产数据库，也没有执行容量、WAL/IO、主备切换、备份恢复、断电和 24/72 小时长稳。这些项目完成并签字前，自动化通过不等于生产放行。
