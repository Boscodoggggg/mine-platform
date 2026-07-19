# 班次、作业单与运输运营闭环设计

## 1. 目标与边界

本切片承接已经通过仓库自动化验收的矿区资产、发生时绑定、双坐标定位、版本化路线/围栏和空间事件，交付可持续扩展的
“通用作业内核 + 首个运输作业模板”。它不是只为演示拼出的运输 MVP：班次、作业单、分配、执行事实、人工修正、审计、
可靠消息和统计口径必须能够继续承载巡检、维护等后续作业类型。

首批业务闭环为：

```text
班次实例开启
  -> 运输作业单发布并分配车辆/司机
  -> 作业启动
  -> 装载点进入/停留/离开
  -> 卸载点进入/停留
  -> 形成一趟不可变执行事实
  -> 班次与作业单运营统计
  -> 班次关账
```

首版不引入 BPM/低代码流程引擎，不实现排产优化、自动派车、铲装协同算法、油耗结算、工资结算、ERP/财务接口和复杂多矿调度。
真实班制、调度规则、装卸阈值、吨位来源、车队规模、容量、HA、备份恢复和 24/72 小时长稳继续标记为 `DEFERRED`。

## 2. 方案选择

采用“管理 PostgreSQL 业务控制面 + mine-server 持久事件消费者 + 追加式事实/修正 + 同事务业务 Outbox”方案。

班次、作业单、分配和运营记录属于低频强一致业务事实，放在管理 PostgreSQL；不把它们写入高频 TimescaleDB，也不让
Telemetry 理解客户作业规则。Telemetry 继续只负责产生不可变 `realtime.spatial.event.created` v1。mine-server 使用独立
持久 RabbitMQ 队列接收空间事件，先写业务库 Inbox/排序队列，再由运输状态机匹配当时有效的班次、作业单和分配。

没有直接复用 WebSocket 独占临时队列，因为临时队列只服务本机在线订阅，重启或无订阅时不能承担业务事实消费。没有让前端
调用空间事件查询后推导作业，因为浏览器离线、重连或多实例会产生不一致。没有引入 BPM，因为首批状态机固定、可测试且需要
单机 Compose 可运行；作业类型注册表和独立状态机接口保留后续扩展边界。

## 3. 领域模型

### 3.1 班次

`ShiftDefinition` 是矿区内稳定班次模板，保存编码、名称、本地开始/结束时间、跨午夜规则和有效日期。`ShiftInstance` 是某个
营业日期的实际班次，保存计划 UTC 时间、实际开关时间、负责人和状态。矿区时区来自 `mine_site`，客户端不得自行换算。

班次状态固定为：

```text
PLANNED -> OPEN -> CLOSED
PLANNED -> CANCELLED
```

关闭后的班次不能新增普通执行记录；历史补录必须使用独立追溯权限和修正事实。班次实例以
`(organization_id, site_id, shift_definition_id, business_date)` 唯一，计划结束必须晚于计划开始且最长不超过 24 小时。

### 3.2 作业单与分配

`WorkOrder` 是计划聚合根。首个 `workType` 固定为 `HAULAGE`，保存矿区内唯一单号、班次实例、路线发布版本、装载/卸载区域
稳定对象及发布版本、计划时间窗、计划趟数/吨位、优先级和状态。作业单发布时冻结路线、区域、计划和业务名称快照，之后主数据
改名不能改变历史展示。

`WorkOrderAssignment` 保存一张作业单内一辆车、司机和有效时间；一张单可分配多车。同一车辆或司机的活动分配时间不得重叠。
分配必须引用同组织、同矿区的活动车辆、当时任职且具备车型资质的司机。作业启动后换车/换司机使用追加式调度变更，不原地覆盖
原分配。

作业单状态固定为：

```text
DRAFT -> RELEASED -> RUNNING -> PAUSED -> RUNNING
RUNNING|PAUSED -> COMPLETED|ABORTED
DRAFT|RELEASED -> CANCELLED
```

发布、启动、暂停、恢复、完成、取消和终止均使用显式动作接口，不开放通用 `status` 更新。

### 3.3 执行与修正

`OperationRuntimeState` 按作业单分配维护当前运输循环状态；`OperationRecord` 保存一次完成或异常终止的不可变执行事实；
`OperationEvidence` 关联装载进入、装载停留、装载离开、卸载进入和卸载停留空间事件。执行记录固化发生时班次、作业单、路线、
区域、车辆、司机、绑定、时间、距离/吨位可选值和来源。

`OperationCorrection` 追加保存补录、修正或作废的原值快照、新值快照、原因、操作人、幂等键和版本。统计使用同事务维护的
`OperationEffectiveRecord` 投影，不直接覆盖 `OperationRecord`。原始空间事件、自动推导事实和历史修正均不得删除。

## 4. 运输状态机与异常

每个活动分配的状态固定为：

```text
WAITING_LOAD
  -> LOADING          （进入装载区域）
  -> LOADED           （装载区域 DWELL）
  -> HAULING          （离开装载区域）
  -> UNLOADING        （进入卸载区域）
  -> COMPLETED        （卸载区域 DWELL，写一趟记录并回到 WAITING_LOAD）
```

空间事件只在区域类型、稳定区域、不可变版本、车辆/标签身份和事件顺序全部匹配时推进。重复事件由 Inbox 和状态机共同幂等。
mine-server 先把事件写入持久排序队列，按 `occurredAt + ingestSequence` 在有界等待窗内排序；超过已确定前沿的迟到事件保留为
候选证据，不让状态倒退。

跳过装载、未停留即离开、重复卸载、路线/车辆不匹配、步骤超时、班次关闭仍未完成和多作业单同时匹配均生成
`OperationCandidate`。只有唯一活动班次、唯一运行作业单和唯一有效分配时才自动推进；平台不能“猜”业务归属。调度员可以把候选
关联到作业、忽略或转为人工补录，每个动作都保留原因和审计。

## 5. 自动识别和时间语义

所有匹配以空间事件 `occurredAt` 为业务时间：

1. 按组织、矿区和事件中的车辆/标签查找发生时有效分配；
2. 分配所属作业单必须处于 `RUNNING`，暂停期间事件只进入候选；
3. 发生时间必须落在作业窗口和 `OPEN` 班次计划/实际边界内；
4. 事件区域版本必须等于发布时冻结的装载或卸载版本；
5. 同一事件只能推进一个运行状态，同一状态转换只能写一次证据；
6. 一趟完成后原子写执行事实、有效投影、状态重置、审计和业务 Outbox。

跨午夜班次按矿区时区的 `businessDate` 归集，不能按 UTC 日期拆分。受控迟到窗口内的事件可以补全尚未关账的状态；`CLOSED`
班次不自动重开，关账后只允许具有追溯修正权限的人工处理。服务时钟只用于租约、接收和审计，不替代事件发生时间。

## 6. 幂等、并发与可靠消息

空间业务消费者使用独立持久队列和手工 ACK。`sys_inbox_event` 以消费者名和空间 `eventId` 去重；Inbox、事件排序记录和认领状态
在业务事务提交后才 ACK。排序/执行领取使用 `FOR UPDATE SKIP LOCKED`、随机 `leaseToken` 和有界退避，旧执行器不能完成新租约。

所有用户写动作要求乐观 `version`；发布、状态迁移、班次开关、候选处置和修正还要求 `Idempotency-Key`。同键同规范化请求返回
第一次结果，同键异请求固定冲突。数据库排斥约束兜底车辆/司机分配重叠，服务端返回可理解的冲突对象和时间。

运营业务通过专用 Outbox 发布 `realtime.operation.updated` v1。空间事件消费、状态推进、执行事实、修正、有效投影、审计和
Outbox 必须在各自命令的单一管理库事务内提交。RabbitMQ 发布失败不回滚已经提交的业务事实；Outbox 使用发布确认、随机租约、
指数退避和 `DEAD` 证据。WebSocket 只消费已确认发布事件，首次订阅或序号缺口通过 REST 快照重同步。

## 7. 数据模型

管理 PostgreSQL 首批表：

- `mine_shift_definition`、`mine_shift_instance`；
- `mine_work_order`、`mine_work_order_assignment`；
- `mine_operation_spatial_event_queue`、`mine_operation_runtime_state`；
- `mine_operation_candidate`；
- `mine_operation_record`、`mine_operation_evidence`；
- `mine_operation_correction`、`mine_operation_effective_record`；
- `mine_operation_outbox`。

所有表必须带组织和矿区标识，业务外键尽量包含作用域列，避免仅凭 UUID 跨矿引用。稳定对象归档或已发布版本不可删除历史引用。
JSONB 只保存规范化快照、证据和修正前后值，核心过滤/统计字段保持关系化。所有表和每个字段必须用
`COMMENT ON TABLE/COLUMN` 写中文注释，集成测试遍历元数据验证完整。

## 8. API 与权限

API 位于 `/api/v1/mine/sites/{siteId}`：

```text
GET/POST          /shift-definitions
GET/PATCH         /shift-definitions/{id}
POST              /shift-definitions/{id}/archive
GET/POST          /shift-instances
POST              /shift-instances/{id}/open|close|cancel

GET/POST          /work-orders
GET/PATCH         /work-orders/{id}
POST              /work-orders/{id}/assignments
POST              /work-orders/{id}/release|start|pause|resume|complete|cancel|abort

GET               /operation-candidates
POST              /operation-candidates/{id}/resolve
GET               /operation-records
GET               /operation-records/{id}
POST              /operation-records/{id}/corrections
GET               /operations/summary
```

权限固定为 `mine:operation:view`、`mine:shift:edit`、`mine:work-order:edit`、`mine:work-order:dispatch`、
`mine:operation:correct` 和 `mine:shift:close`。全部接口同时校验组织/矿区数据范围；页面 `siteId` 不是授权凭据。追溯补录和作废
使用 `mine:operation:correct`，关闭班次使用更高风险的 `mine:shift:close`。

## 9. 前端产品形态

“矿区管理”下新增单一“生产运营”菜单，避免把班次、作业单、记录和统计拆成多个低密度菜单。工作台包含：

- 班次：模板、当日实例、开启/关闭和关账提示；
- 作业单：计划、分配、发布和状态时间线；
- 待确认：歧义、迟到、步骤异常和人工处置；
- 执行记录：单趟证据链、修正历史和作废状态；
- 运营看板：计划/完成趟数、完成率、吨位、阶段耗时、异常、车辆利用率和司机作业量。

作业详情必须展示冻结路线/区域版本和发生时身份，不能只显示当前主数据。高风险操作使用确认弹窗并展示影响；无对应按钮权限时
保持只读。业务页面使用 Naive UI、UnoCSS、主题 token 和 Vue I18n，不添加页面 `<style>`、内联 CSS、硬编码颜色或用户文案。

## 10. 统计口径

统计只读取 `mine_operation_effective_record`，不直接扫描空间事件或在浏览器计算。首批指标：计划趟数、有效完成趟数、完成率、
计划/实际吨位、平均单趟时长、装载/运输/卸载平均耗时、异常趟次、车辆有效作业时长/班次开放时长、司机有效趟数。

作废记录不计完成量但保留在审计统计；修正使用最新有效投影并保留修正次数。缺少吨位不得当作 0，返回 `null/unknown` 与已知
零吨分开。摘要查询强制矿区、营业日期或时间窗，最长 31 天并使用固定水位，禁止无界扫描。

## 11. 可观测性、安全失败与回退

指标只使用固定 `stage/result/reason/workType/candidateType` 标签，禁止组织、矿区、作业单、车辆、司机、事件标识和异常文本。
告警至少覆盖空间事件业务队列积压、过晚事件、状态推进失败、候选突增、Outbox DEAD 和班次关账遗留状态。

自动识别故障时停止业务消费者但保留队列、Inbox、运行状态和事实；不能清队列或删除证据。空间事件实时展示故障不影响业务持久
消费者，业务消费者故障也不回滚 Telemetry 空间事实。错误作业配置通过取消/终止和新建正确作业处理，已发布/已完成事实不原地
改写。恢复后有界排空队列，超过关账边界的事件进入候选而不是静默丢弃。

## 12. 自动化成功标准

- 迁移从空库和升级库通过，全部表/字段中文注释、作用域外键、排斥约束、状态/时间边界和不可变事实测试通过；
- 纯班次、作业单和运输状态机覆盖全部允许/拒绝迁移、重复、乱序、迟到、异常和一趟完成；
- 空间事件持久队列、Inbox、租约、排序、唯一匹配、候选、状态恢复和同事务执行事实通过真实 PostgreSQL/RabbitMQ 集成测试；
- API 精确 RBAC、矿区隔离、乐观锁、幂等、冻结版本、人工修正、关账和 OpenAPI 契约通过；
- Outbox 发布确认、退避、DEAD、实时快照/增量和缺口重同步通过；
- 前端加载/空/错/权限/高风险确认/矿区切换/主题/中英文/窄屏和 UI 治理通过；
- 统计对计划、修正、作废、缺失吨位、跨午夜和固定水位的口径测试通过；
- Maven、前端、Compose、Prometheus 和必要 E2E 全量门禁通过，现场项保持 `DEFERRED`，不得宣称已取得生产放行资格。
