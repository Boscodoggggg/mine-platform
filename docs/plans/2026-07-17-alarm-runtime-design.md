# Phase 2 双来源告警运行与处置闭环设计

## 1. 目标与范围

本切片在现有三级策略控制面、Telemetry 数据质量执行链路和可靠消息骨架上，交付可运营的告警纵向闭环：

1. 管理员以 `ALARM` 策略配置数值阈值告警和数据质量命中告警；
2. `mine-telemetry` 使用本地最后正确策略镜像，在遥测持久事务中推进持久告警状态机；
3. 告警触发和恢复事件通过 Telemetry Outbox 可靠发布；
4. `mine-server` 使用 Inbox 幂等落库，并提供查询、详情、确认和关闭 API；
5. Web 端在策略工作台配置告警，在告警中心完成值班处置；
6. 全链路具备审计、权限、指标、告警规则、运行手册和非现场自动化验收。

本切片不执行真实设备、真实矿区网络、外部通知送达、容量、HA、备份恢复和 24/72 小时长稳验证，也不把自动化通过表述为生产放行。

## 2. 方案选择

### 方案 A：仅支持数值阈值

实现较小，但数据质量证据仍停留在技术指标，无法进入值班人员的确认、恢复和审计流程，不能形成可销售产品需要的运营闭环。

### 方案 B：数值阈值 + 数据质量命中双来源

数值来源覆盖温度、压力、液位等设备业务指标；质量来源复用已落地的时间可信度、必填和上下限证据。两类规则共享持续时间、恢复时间、去重、冷却、处置和审计模型。

采用方案 B。

### 方案 C：开放任意表达式或脚本 DSL

首版不采用。任意表达式会引入资源耗尽、脚本注入、不可预测执行时间、版本兼容和现场调试成本。后续跨属性、窗口统计和组合事件应通过版本化白名单算子扩展，不能绕过发布门禁直接执行用户脚本。

## 3. 受控策略契约

新增稳定策略类型 `ALARM`。策略只有一个顶层字段 `rules`，值为有序规则集合；它作为一个原子字段参与产品默认、设备组覆盖和单设备覆盖，不做规则内部的跨作用域拼接。下级需要覆盖时必须提交完整规则集，工作台显示整组规则的最终来源。

公共字段：

| 字段 | 约束 | 语义 |
|---|---|---|
| `key` | 产品内稳定编码，1～64 字符 | 设备告警状态和去重业务键的一部分 |
| `name` | 1～100 字符 | 面向值班人员的规则名称 |
| `sourceType` | `NUMERIC` / `QUALITY` | 规则来源 |
| `severity` | `INFO` / `WARNING` / `CRITICAL` | 告警等级 |
| `triggerDurationSeconds` | 0～86400 | 条件连续成立多久才触发 |
| `recoverDurationSeconds` | 0～86400 | 恢复条件连续成立多久才恢复 |
| `cooldownSeconds` | 0～604800 | 一个周期恢复后再次触发前的冷却时间 |
| `enabled` | 布尔值 | 是否执行；禁用不删除历史周期 |

数值规则额外字段：

| 字段 | 约束 | 语义 |
|---|---|---|
| `propertyId` | 合法物模型属性标识 | 读取本帧数值属性 |
| `operator` | `GT` / `GTE` / `LT` / `LTE` | 触发比较算子 |
| `threshold` | 有限十进制，绝对值不超过 10^15 | 触发阈值 |
| `recoverThreshold` | 同上 | 独立恢复阈值，形成迟滞区间 |

高值规则 `GT/GTE` 要求 `recoverThreshold <= threshold`，低值规则 `LT/LTE` 要求 `recoverThreshold >= threshold`。质量为 `BAD`、`TIME_UNTRUSTED` 的属性不能驱动数值告警；属性缺失或质量不可用只保留当前候选状态，不把“未知”解释为恢复。

质量规则额外字段：

| 字段 | 约束 | 语义 |
|---|---|---|
| `qualityRuleCode` | 当前质量规则编码白名单 | 匹配本帧结构化质量证据 |
| `propertyId` | 可选合法属性标识 | 为空时匹配该编码的任意属性，否则精确匹配 |

白名单为 `FUTURE_TIMESTAMP`、`STALE_TIMESTAMP`、`REQUIRED_MISSING`、`BELOW_MINIMUM`、`ABOVE_MAXIMUM`。同一规则在一帧出现一个或多个匹配证据都只算一次命中；连续未命中进入恢复候选。

每份策略最多 100 条规则，规则键不得重复，JSON 总体继续受现有 API 请求体上限约束。策略定义负责把数字规范化为稳定十进制，并拒绝未知字段、错配字段、空规则集、非法迟滞和超限配置。

## 4. 时间、顺序和实时边界

告警持续时间使用平台可信的 `receivedAt`，不使用设备自报时间。状态机只处理已经被持久最新值顺序状态接受的实时帧：

- QoS 1 重投由 `telemetry_inbox` 幂等阻止重复推进；
- 旧 boot、低序号、补传和乱序帧仍保留历史与质量证据，但默认不触发或恢复实时告警；
- 平台接收时间倒退的帧不推进持续时间；
- 本帧不包含目标属性时，数值规则保持状态，不推断为正常；
- 未来若需要历史告警，使用独立的离线回算标记和通知策略，不能复用实时状态机冒充。

这样可以避免迟到补传在值班端制造告警风暴，也不会因缺值把活动告警错误恢复。

## 5. Telemetry 持久状态机

### 5.1 状态与业务键

每个 `(device_id, rule_key)` 只有一条当前运行状态：

~~~text
NORMAL
  └─ 条件成立 → FIRING_PENDING
       ├─ 条件失效 → NORMAL
       └─ 达到触发持续时间 → ACTIVE
            └─ 恢复条件成立 → RECOVERING_PENDING
                 ├─ 再次触发 → ACTIVE
                 └─ 达到恢复持续时间 → COOLDOWN
                      └─ 冷却到期且新条件成立 → FIRING_PENDING
~~~

数据库保存候选起点、当前活动周期标识、最近可信接收时间、最近观测摘要、策略快照与内容哈希、冷却截止时间和乐观版本。触发时生成 UUIDv7 `alarmOccurrenceId`；同一周期的 raised/recovered 共享该标识。

### 5.2 事务原子性

告警计算必须进入 `TelemetryBatchWriter` 的数据库事务，与以下事实一起提交：

- 遥测 Inbox；
- 数值历史和持久最新值；
- 数据质量证据；
- 告警状态变更；
- `telemetry_outbox_event` raised/recovered 事件。

事务提交后才能刷新 Redis、发布实时遥测并 ACK 原始消息。Telemetry Outbox relay 使用数据库认领、租约、指数退避、启动有界恢复和 RabbitMQ publisher confirm；发布成功后标记 `PUBLISHED`。告警可靠性不依赖进程内回调或内存定时器。

### 5.3 策略变更

状态行保存 `policyContentHash` 和规则指纹。新快照激活时：

- 未变化规则继续原状态；
- 新增规则从 `NORMAL` 开始；
- 已禁用、删除或语义改变的活动规则，在快照激活事务中产生 `alarm.recovered`，恢复原因为 `RULE_DISABLED`、`RULE_REMOVED` 或 `RULE_CHANGED`；
- 非活动旧状态安全删除或重置；
- 快照激活失败保留上一最后正确版本及其状态。

规则键是运维可见身份，修改规则含义时工作台提示优先创建新键；即使误复用，规则指纹也会关闭旧周期，避免把两个语义混为一个告警。

## 6. 可靠事件契约

新增业务交换机 `mine.business.x`，路由键 `alarm.raised`、`alarm.recovered`，Server 使用 quorum 队列 `mine.server.alarm-event.q` 手工 ACK 消费。两类事件均携带足以独立落库的完整身份，允许恢复事件先到达：

- 事件信封：`eventId`、`eventType`、`schemaVersion`、`occurredAt`、`traceId`；
- 告警周期：`alarmOccurrenceId`、`deviceId`、`productKey`；
- 规则：`ruleKey`、`ruleName`、`sourceType`、`severity`、`propertyId`；
- 证据：触发或恢复时的观测值、阈值、质量规则编码，均为有界结构；
- 策略：`policyRuntimeRevision`、`policySnapshotId`、`policyContentHash`；
- 时间：`raisedAt`，恢复事件另含 `recoveredAt` 和 `recoveryReason`。

消息不包含凭据、任意异常堆栈或未经限制的大型原始报文。Server 用 `sys_inbox_event` 对 `eventId` 幂等，用 `alarmOccurrenceId` 对业务周期幂等。恢复早到时可以直接 upsert 完整记录，后续 raised 只补充而不回退状态。

## 7. Server 告警领域模型

### 7.1 正交事实而非脆弱线性枚举

现实中告警可能先恢复后由值班员确认，因此数据库保存正交事实：

- `raised_at`：必有；
- `recovered_at`：Telemetry 恢复后填写；
- `acknowledged_at`：人工确认后填写；
- `closed_at`：人工关闭后填写。

API 派生状态为 `ACTIVE_UNACKNOWLEDGED`、`ACTIVE_ACKNOWLEDGED`、`RECOVERED_UNACKNOWLEDGED`、`RECOVERED_ACKNOWLEDGED`、`CLOSED`。这保留了文档中的触发、确认、恢复、关闭语义，又支持恢复先于确认的真实顺序。

`alarm_event` 保存告警周期、设备与规则快照、策略证据、raised/recovered/closed 事实和版本；`alarm_acknowledgement` 保存操作者、时间、说明及请求幂等键。确认采用一次性事实，重复同幂等键返回原结果，不同键重复确认返回当前结果而不重复写审计。

### 7.2 API 与权限

- `GET /api/v1/alarms`：按活动性、状态、等级、设备、规则和时间范围游标/分页查询；
- `GET /api/v1/alarms/{id}`：详情、证据和处置时间线；
- `POST /api/v1/alarms/{id}/acknowledgements`：确认，要求 `Idempotency-Key` 和非空说明；
- `POST /api/v1/alarms/{id}/close`：仅已恢复告警可关闭，要求 `Idempotency-Key` 和关闭说明。

权限为 `alarm:event:view`、`alarm:event:ack`、`alarm:event:close`。后端是最终鉴权边界；确认和关闭记录结构化操作审计，只记录必要前后状态、告警标识和说明摘要，不写完整消息或敏感信息。

## 8. 前端产品体验

策略工作台增加 `ALARM` 类型和规则编辑器：

- 数值/质量来源切换后只展示对应白名单字段；
- 规则卡片支持新增、复制、排序、启停和删除；
- 阈值与恢复阈值在前端即时提示，后端重新校验；
- 明确显示整组规则的产品、设备组或设备来源以及“覆盖会替换完整规则集”的提示；
- 发布和回滚继续复用原因、幂等键、历史和冲突门禁。

新增告警中心，默认展示活动与未关闭告警，支持状态/等级/设备筛选、详情抽屉、确认和关闭。恢复但尚未确认的告警仍保留在待处置视图；权限不足时不显示操作按钮，API 仍强制鉴权。全部可见文案进入中英文资源，使用 Naive UI 和主题 token，不新增页面私有样式。

## 9. 可观测性、安全与验收

低基数指标至少包括：规则评估结果、状态迁移结果、活动告警数量（按等级）、Outbox 待发布/失败、Server 消费成功/重复/失败、确认/关闭结果。设备标识、规则键和告警标识不得成为 Prometheus 标签。

Prometheus 规则覆盖 Telemetry Outbox 持续堆积、告警消费者持续失败、活动严重告警异常增长；运行手册覆盖策略回退、积压排查、重复事件、恢复早到、规则变更和人工处置。

自动化验收覆盖：

- 策略白名单、迟滞、整组覆盖和同级冲突；
- 持续时间、抖动、冷却、缺值、质量不可用、乱序、重投和规则变更；
- 告警状态与 Outbox 原子提交、confirm 重试和启动恢复；
- Server 事件乱序/重复、确认/关闭幂等、权限、审计和并发版本；
- PostgreSQL/TimescaleDB 新表、全部中文表/字段注释、约束和索引；
- Web 中英文、亮暗主题、UI 治理、单元测试、构建和 Chromium E2E；
- 全仓 `scripts/verify.sh`。

