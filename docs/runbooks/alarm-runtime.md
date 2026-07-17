# 告警运行与处置手册

## 1. 适用范围

本手册用于 `ALARM` 策略发布观察、Telemetry 持久告警排障、可靠事件积压处理以及告警中心人工确认/关闭。适用组件包括 `mine-server`、`mine-telemetry`、RabbitMQ、业务 PostgreSQL、TimescaleDB 和 Prometheus。

当前只支持数值阈值和数据质量命中两类规则。短信、电话、企业 IM、工单升级、现场阈值调优、真实设备、矿区弱网、容量、HA、备份恢复和 24/72 小时长稳不在本次自动化验收范围。所有 SQL 只允许使用只读运维账号；禁止直接更新策略镜像、运行状态、Outbox、Inbox 或告警业务表。

## 2. 发布前检查

1. 确认规则 `key` 是产品内稳定运维身份。修改规则业务含义时优先创建新键，不要把两个含义复用为同一周期。
2. 数值规则必须核对物模型属性、单位、厂商量程、比较算子和恢复阈值。高值规则要求恢复阈值不高于触发阈值，低值规则要求恢复阈值不低于触发阈值。
3. 质量规则必须确认对应 `DATA_QUALITY` 规则确实会在目标设备执行。`propertyId` 为空表示匹配该质量规则的任意属性，不是“没有属性”。
4. 结合上报频率设定触发/恢复持续时间和冷却时间。持续时间按平台 `receivedAt` 计算，不能用设备自报时间推算。
5. 产品默认、设备组和单设备的 `rules` 是整组覆盖，不是逐规则合并。发布前检查有效配置面板、来源、规则顺序、启停状态和内容哈希。
6. 记录变更原因、责任人、预期影响设备和回退版本；通过受权工作台发布并复用稳定 `Idempotency-Key`，不得直接修改 `dev_strategy_*` 表。

## 3. 发布后确认

先观察共享策略分发链路是否健康。以下指标由 `DATA_QUALITY` 和 `ALARM` 共用，不能单独证明告警快照已加载；告警修订以随后 SQL 为准：

~~~promql
increase(mine_telemetry_strategy_distribution_total{outcome="loaded"}[10m])
increase(mine_telemetry_strategy_distribution_total{outcome=~"permanent_failure|invalid_message"}[10m])
increase(mine_telemetry_strategy_distribution_total{outcome="transient_failure"}[10m])
~~~

使用 TimescaleDB 只读账号核对运行水位和镜像：

~~~sql
SELECT product_id, runtime_revision, snapshot_id, item_count, content_hash, applied_at
FROM telemetry_strategy_runtime_revision
WHERE strategy_type = 'ALARM'
ORDER BY applied_at DESC;

SELECT product_id, runtime_revision, snapshot_id, count(*) AS device_count
FROM telemetry_alarm_policy
GROUP BY product_id, runtime_revision, snapshot_id
ORDER BY runtime_revision DESC;
~~~

修订必须单调增加，快照和哈希应与控制面一致。某台设备没有有效 `ALARM` 策略时，本地没有策略镜像是合法状态；Telemetry 不会为它凭空创建告警。

再检查运行状态和可靠事件是否收敛：

~~~sql
SELECT state, severity, count(*) AS state_count
FROM telemetry_alarm_runtime_state
GROUP BY state, severity
ORDER BY state, severity;

SELECT status, count(*) AS event_count, min(created_at) AS oldest_created_at
FROM telemetry_outbox_event
GROUP BY status
ORDER BY status;
~~~

`FAILED` 应在退避后恢复，`PENDING/PUBLISHING` 不应持续增长，`DEAD` 必须人工处置。不要通过删除 Outbox 记录消除告警。

## 4. 可靠事件排障

### 4.1 `MineTelemetryAlarmOutboxBacklog`

1. 检查 `mine.business.x` 到告警队列的路由与绑定，再检查 `mine.server.alarm-event.q` 的 ready/unacked、最老消息年龄和 RabbitMQ 磁盘/内存水位。
2. 检查 Telemetry publisher confirm、网络、最小权限账号和 `PENDING/PUBLISHING` 最老事件。
3. 恢复后确认积压下降、`PUBLISHED` 增长，并核对告警中心存在对应 `alarmOccurrenceId`。

### 4.2 `MineTelemetryAlarmOutboxFailed` / `MineTelemetryAlarmOutboxDead`

1. 按 `event_id` 关联 Telemetry 脱敏日志、Outbox 状态、事件类型和最后错误；不要把异常文本放入 Prometheus 标签。
2. 核对业务交换机路由、publisher confirm、消息大小与共享告警契约。
3. `FAILED` 由同一 `eventId` 精确重试；`DEAD` 表示尝试已耗尽。当前版本没有面向运维的死信重放 API，必须保留证据、修复根因并升级研发处置，不能改库伪造为已发布。

### 4.3 `MineServerAlarmConsumeFailures`

1. `requeued` 表示数据库或依赖瞬时失败，应保持消息未确认并检查业务 PostgreSQL、连接池和队列积压。
2. `rejected` 表示身份、结构版本、时间或证据冲突等永久错误；保留死信并核对生产者和消费者版本。
3. 恢复事件先到、触发事件后到是受支持场景。Server 会先创建恢复周期再补触发证据；不得把它误判为数据损坏。
4. 同一消息重投由 `sys_inbox_event` 短路。同一周期出现不同身份或不同恢复事实时必须调查，不能手工覆盖。

### 4.4 `MineCriticalActiveAlarmsGrowing`

1. 进入告警中心，以未关闭视图按 `CRITICAL`、设备和规则筛选，确认增长来自单设备、同类设备还是全矿区共同原因。
2. 查看触发证据、属性质量、策略修订/哈希和规则来源，区分真实风险、映射错误、单位错误、质量故障或阈值过紧。
3. 真实风险按矿区应急流程处置；策略误报按第 6 节创建更高回退发布。不要为了让图表归零而关闭活动告警。

## 5. 值班确认与关闭

1. 告警中心默认显示全部未关闭周期，包括活动、已恢复未确认和已恢复已确认。
2. 确认表示值班人员已接手，不表示条件已经恢复。确认前查看触发证据并填写可审计说明。
3. 网络超时后使用原 `Idempotency-Key` 重试；不得换键连续点击。服务端只保存首次确认并避免重复成功审计。
4. 恢复可以早于确认。确认后仍需等待恢复事实；已恢复未确认的告警仍应完成确认或按现场制度处置。
5. 只有已恢复告警可以关闭。关闭前核对恢复证据和原因，填写说明；活动告警关闭会返回 `ALARM-409-002`。
6. `ALARM-409-001` 表示版本已变化，应刷新详情，基于最新事实重新决定，不得盲目覆盖。

使用业务 PostgreSQL 只读账号抽查告警事实：

~~~sql
SELECT id, device_id, rule_key, severity, raised_at, recovered_at,
       acknowledged_at, closed_at, recovery_reason,
       policy_runtime_revision, policy_snapshot_id, policy_content_hash, version
FROM alarm_event
ORDER BY raised_at DESC
LIMIT 200;
~~~

## 6. 安全回退

1. 在策略工作台选择目标作用域和 `ALARM`，找到最后已知正确的历史发布，核对完整规则集合、来源和哈希。
2. 填写明确回退原因并二次确认；重复提交始终复用同一个 `Idempotency-Key`。
3. 回退会创建更高发布序号、运行修订和新快照，不会修改历史。Telemetry 完成暂存与校验后原子切换最后正确镜像。
4. 被删除、禁用或语义变化的旧活动规则会产生 `RULE_REMOVED`、`RULE_DISABLED` 或 `RULE_CHANGED` 恢复事件，再移除旧运行状态。
5. 按第 3 节确认新修订已加载、Outbox 已收敛，并在告警中心核对旧周期恢复原因和新规则行为。

禁止降低修订号、覆盖 `telemetry_alarm_policy`、删除状态行、修改 `alarm_event` 或把 `DEAD` 直接改成 `PUBLISHED`。这些操作会破坏不可变历史、至少一次投递和审计证据。

## 7. 升级与恢复不变量

- 控制面发布、Telemetry 运行水位、单设备镜像、状态机和告警周期必须能通过修订、快照与哈希关联。
- 遥测事务未提交前不得发布告警事件；publisher confirm 前不得把 Outbox 标记为成功。
- 乱序和重复事件不得产生第二个告警周期、第二次确认或重复成功审计。
- 规则变更不得把旧活动周期静默丢弃，也不得把新旧语义合并。
- RabbitMQ 和 Server 暂时故障期间，Telemetry 的最后正确策略与持久状态继续保留。
- 真实通知送达、现场阈值、双节点追赶、容量、HA、备份恢复、断电和 24/72 小时长稳是独立上线门禁，本手册中的自动化和只读检查不能替代生产验收。
