# 矿区生产运营运行手册

## 1. 适用范围

本手册面向矿区调度员、值班负责人、平台管理员和交付工程师，覆盖班次模板与实例、运输作业单、车辆/司机分配、空间事件自动识别、候选处置、人工补录与修正、运营统计、实时增量、指标告警和安全恢复。

当前能力已经通过仓库自动化和容器集成验证，可用于演示、联调和继续产品化开发；它不替代真实矿区班制、调度规则、定位/区域参数、吨位来源、目标规模、高可用、备份恢复和长稳验收。生产放行边界见
[Phase 3 班次、作业单与生产运营自动化验收报告](../reports/phase-3-production-operation-acceptance.md)。

## 2. 运行链路与不变量

~~~text
已发布路线/装载区/卸载区 + 开放班次 + 已发布并启动的运输作业单
→ 车辆和可选司机的有效时间分配
→ realtime.spatial.event.created v1
→ RabbitMQ 最小权限运营消费账号
→ 持久事件队列、事件时间水位、租约和有界重试
→ WAITING_LOAD → LOADING → LOADED → HAULING → UNLOADING → COMPLETED
→ 不可变执行事实、固定角色证据和当前有效投影
→ 同事务运营 Outbox
→ realtime.operation.updated v1
→ MINE_SITE_OPERATION WebSocket + REST 固定水位回查
→ 生产运营工作台和统计
~~~

必须始终满足：

1. 客户端 `siteId` 不是授权凭据，接口同时校验权限、组织和矿区数据范围。
2. 班次和作业单只允许显式状态迁移；终态不能通过直接更新 `status` 恢复。
3. 作业单发布时冻结路线、装载区、卸载区版本以及车辆/司机显示快照；历史不跟随主数据改名或新版本漂移。
4. 一趟运输只接受 `LOAD_ENTER → LOAD_DWELL → LOAD_EXIT → UNLOAD_ENTER → UNLOAD_DWELL` 的严格证据前缀。
5. 不唯一、暂停、关账、过晚、身份/区域不匹配和顺序异常的事件进入候选或固定跳过，不得猜测归属或静默丢弃。
6. 自动事实和原始证据不可修改；人工操作只追加 `ADD/AMEND/VOID/RESTORE` 修正并保留原因、操作人、幂等键和投影版本。
7. 数据库运营事实是事实源；WebSocket 和 Outbox 故障不能反向篡改已经提交的事实。

## 3. 启用前检查

### 3.1 服务与配置

Standalone 部署显式启用以下两条独立链路；应用默认值为关闭，避免未准备 RabbitMQ 账号和队列时误启动：

| 配置 | Standalone 默认 | 作用 |
|---|---:|---|
| `MINE_OPERATION_RUNTIME_RABBIT_ENABLED` | `true` | 消费空间事件并持久化、自动推进运营循环 |
| `MINE_OPERATION_RUNTIME_AUTO_STARTUP` | `true` | 服务启动后启动运营监听容器 |
| `MINE_OPERATION_RUNTIME_ALLOWED_LATENESS` | `30s` | 运营事件允许乱序窗口 |
| `MINE_OPERATION_RUNTIME_IDLE_FLUSH` | `5s` | 无新事件时的水位排空等待 |
| `MINE_OPERATION_RUNTIME_STEP_TIMEOUT` | `2h` | 单个运输步骤最长持续时间 |
| `MINE_OPERATION_RUNTIME_DRAIN_LIMIT` | `100` | 单轮有界处理数量 |
| `MINE_OPERATION_OUTBOX_ENABLED` | `true` | 可靠发布已提交运营变更 |
| `MINE_OPERATION_OUTBOX_MAX_ATTEMPTS` | `8` | 发布最大尝试次数 |
| `MINE_OPERATION_OUTBOX_RECOVERY_INTERVAL` | `1s` | 待发布事实恢复间隔 |

运营消费账号 `mine-server-operation` 只能读取 `/mine-platform` 的 `mine.server.operation-spatial.q`；运营发布账号
`mine-server-operation-outbox` 只能写入 `mine.realtime.x`。密码只从只读 Secret 文件加载，不能放入环境变量、日志或前端配置。

发布前执行：

```bash
bash deploy/tests/compose-config.sh
bash deploy/tests/prometheus-rules.sh
docker compose -f deploy/docker-compose.yml config --quiet
```

### 3.2 主数据与权限

确认以下条件全部成立：

- 矿区时区正确，班次模板本地起止时间和跨午夜标识已经由业务负责人复核。
- 车辆为活动状态；可选司机具有目标车型和事件时刻有效的矿区任职/驾驶资质。
- 路线、装载区域和卸载区域已经发布，属于同一矿区和统一坐标系，版本有效期覆盖计划窗口。
- 空间事件链路已按 [矿区路线、围栏与空间事件运行手册](mine-spatial-event-runtime.md) 验证。
- 调度员至少具有 `mine:operation:view`；按职责最小授予 `mine:shift:edit`、`mine:shift:close`、`mine:work-order:edit`、`mine:work-order:dispatch`、`mine:operation:correct`。
- 菜单位于“矿区管理 / 生产运营”，路径 `/mine/operations`，组件白名单键为 `mine-production-operations`。

## 4. 标准生产操作

### 4.1 班次准备与开启

在生产运营工作台的“班次”页签完成：

1. 创建活动班次模板，填写稳定编码、本地起止时间、是否跨午夜和有效营业日期。
2. 按营业日期创建 `PLANNED` 班次实例并可选择值班负责人。
3. 复核系统计算的计划起止时刻和矿区时区。
4. 使用当前乐观版本和稳定 `Idempotency-Key` 开启班次，状态变为 `OPEN`。

班次状态只允许：

~~~text
PLANNED → OPEN → CLOSED
    └────────→ CANCELLED
~~~

相同幂等键重试必须保持同一动作和请求；收到 `409` 时刷新详情，禁止更换幂等键盲目重复提交。

### 4.2 作业单计划、分配、发布和启动

1. 在开放或计划班次内创建 `HAULAGE` 草稿，填写矿区内唯一作业单号、计划窗口、计划趟数/吨数和优先级。
2. 追加车辆和可选司机分配，明确半开有效时间区间和分配原因。系统会校验车辆类型、司机资质和时间冲突。
3. 发布前复核路线版本、装载区域版本、卸载区域版本及全部调度快照。
4. 以持久幂等键发布，冻结计划和空间版本；开启班次后再启动作业。
5. 生产中只通过显式动作暂停、恢复、完成或异常终止，并填写取消/异常原因。

作业单状态只允许：

~~~text
DRAFT → RELEASED → RUNNING ⇄ PAUSED → COMPLETED
   └──────────────→ CANCELLED
                    RUNNING/PAUSED → ABORTED
~~~

暂停只阻止自动推进，不能删除已经接收的空间事实；恢复后仍按事件发生时的状态历史判定，不能把暂停窗口内的事件伪装成正常生产。

### 4.3 自动识别与确定性模拟

自动识别要求事件时刻存在唯一活动作业分配、班次为 `OPEN`、作业单处于 `RUNNING`，车辆身份和区域版本与发布快照一致。完整循环形成一条不可变 `COMPLETED` 执行记录，并自动创建下一趟等待装载状态。

在非生产矿区可从 <http://127.0.0.1:18090> 选择定位场景：

| 场景 | 固定行为 | 预期 |
|---|---|---|
| `HAUL_CYCLE` | 装载区中心 `(100,100)` 停留，经运输道路到卸载区中心 `(500,400)` 停留并返回 | 形成完整运输记录并进入下一趟 |
| `HAUL_CYCLE_SKIP_LOAD_DWELL` | 在达到装载停留阈值前离开 | 不形成完整装载证据，异常事实可进入候选 |
| `HAUL_CYCLE_REPEAT_UNLOAD` | 完成首次卸载后离开并再次进入卸载区 | 首次循环只形成一次记录，重复/异常事件不产生重复事实 |

只有已发布装载/卸载区域与上述固定几何、停留阈值和模拟周期一致时，场景才产生预期业务结果。模拟场景不是现场验证证据。

## 5. 候选、补录和修正

### 5.1 候选处置

候选默认保持 `PENDING`，调度员必须查看事件时间、车辆/标签、区域版本、原因码和建议归属后再处理：

- `ASSOCIATE`：确认候选属于指定作业单和分配；只建立受审计关联，不改写空间事件。
- `IGNORE`：确认该事件不应形成运营事实；必须填写可审计原因。
- `CONVERT_MANUAL`：将候选连同结构化人工内容转为补录记录，仍保留原候选和证据关联。

常见原因包括车辆/标签身份不唯一、没有唯一活动分配、班次未开放、作业暂停或未运行、区域版本不匹配、步骤超时、过晚和顺序异常。不得通过删除候选来降低待办数量。

### 5.2 人工补录和追加修正

`mine:operation:correct` 是高风险追溯权限。每次操作必须使用稳定 `Idempotency-Key`、业务原因和当前投影版本：

- `ADD`：补录一条具有作业单、分配、起止时间和结果的不可变事实。
- `AMEND`：追加修正距离、吨位或结构化字段，原始事实不变。
- `VOID`：使当前投影不再参与统计，原事实和修正链仍可追溯。
- `RESTORE`：在投影版本匹配时恢复此前作废事实。

吨位来源必须明确；未完成现场称重/载荷传感器标定前，不得把模拟值或人工估算标记为已校准实测吨位。

## 6. 查询、实时和日常核查

记录查询使用营业日期窗口，最长 31 天；首页返回固定 `watermark`，后续页必须原样传回该水位和不透明 `cursor`。页面订阅
`MINE_SITE_OPERATION`，按矿区内连续 `businessSequence` 去重；发现缺口、重连或订阅失败时重新读取当前页，不能在浏览器内猜测遗漏状态。

主要接口：

~~~text
GET/POST /api/v1/mine/sites/{siteId}/shift-definitions
GET/POST /api/v1/mine/sites/{siteId}/shift-instances
POST     /api/v1/mine/sites/{siteId}/shift-instances/{shiftId}/open|close|cancel
GET/POST /api/v1/mine/sites/{siteId}/work-orders
POST     /api/v1/mine/sites/{siteId}/work-orders/{workOrderId}/assignments
POST     /api/v1/mine/sites/{siteId}/work-orders/{workOrderId}/release|start|pause|resume|complete|cancel|abort
GET      /api/v1/mine/sites/{siteId}/operation-candidates
POST     /api/v1/mine/sites/{siteId}/operation-candidates/{candidateId}/resolve
GET/POST /api/v1/mine/sites/{siteId}/operation-records
POST     /api/v1/mine/sites/{siteId}/operation-records/{recordId}/corrections
GET      /api/v1/mine/sites/{siteId}/operation-summary
~~~

数据库只读核查示例：

```sql
SELECT status, COUNT(*)
FROM mine_operation_spatial_event_queue
GROUP BY status
ORDER BY status;

SELECT candidate_type, reason_code, status, COUNT(*)
FROM mine_operation_candidate
GROUP BY candidate_type, reason_code, status
ORDER BY status, candidate_type, reason_code;

SELECT status, COUNT(*)
FROM mine_operation_outbox
GROUP BY status
ORDER BY status;

SELECT shift_instance.id, shift_instance.business_date,
       shift_instance.planned_end_at, shift_instance.status
FROM mine_shift_instance shift_instance
WHERE shift_instance.status = 'OPEN'
  AND shift_instance.planned_end_at <= CURRENT_TIMESTAMP
ORDER BY shift_instance.planned_end_at;
```

上述查询只用于核查，禁止直接更新队列、运行态、候选、记录、有效投影、修正、Outbox 或业务序号表。

## 7. 指标和告警

指标标签只允许固定 `stage/result/reason/workType/candidateType`，禁止加入组织、矿区、作业单、车辆、司机、人员、空间事件和异常文本。

| 指标 | 作用 |
|---|---|
| `mine_server_operation_queue_events_total` | 持久队列成功、跳过、失败和原因计数 |
| `mine_server_operation_stage_events_total` | 自动化、Outbox、关账等阶段结果 |
| `mine_server_operation_candidates_total` | 首次创建的固定类型候选 |
| `mine_server_operation_records_total` | 形成不可变执行事实的结果 |
| `mine_server_operation_queue_backlog` | `PENDING/FAILED` 队列积压 |
| `mine_server_operation_outbox_dead` | 运营实时 Outbox 死信 |
| `mine_server_operation_shift_close_leftovers` | 到期开放班次的未终结作业/循环数 |

默认告警：

- `MineOperationQueueBacklogHigh`：积压超过 1000 且持续 5 分钟。
- `MineOperationTooLateRateHigh`：10 分钟至少 100 个样本且过晚比例超过 5%，持续 5 分钟。
- `MineOperationStateAdvanceFailures`：10 分钟内出现状态推进失败或死信，持续 1 分钟。
- `MineOperationCandidateSpike`：10 分钟新增候选不少于 20 条，持续 5 分钟。
- `MineOperationOutboxDead`：Outbox 死信大于 0 且持续 1 分钟。
- `MineOperationShiftCloseLeftovers`：到期开放班次存在遗留并持续 15 分钟。

## 8. 故障处置和安全恢复

### 8.1 队列积压

先确认 RabbitMQ 消费者、数据库连接/锁、工作器线程、事件时间水位和单轮上限，再区分持续输入过高与处理停滞。必要时先停止非生产模拟或上游流量；不得清空队列、批量改为 `DONE`，也不得无限放大迟到窗口掩盖时钟问题。修复后有界排空并观察积压、失败率和候选量。

### 8.2 过晚比例升高

核对设备/Gateway 时间源、网络补传、空间事件发生时间和 `MINE_OPERATION_RUNTIME_ALLOWED_LATENESS`。只有业务和容量证据支持时才调整窗口；已经固定跳过的过晚事件不能回滚运行状态，确需形成事实时走人工补录或候选处置。

### 8.3 状态推进失败或候选突增

检查班次是否开放、作业状态历史、分配有效期、车辆/标签发生时绑定、冻结区域版本、事件顺序和步骤超时。先修复主数据或调度流程，再由正常重试/人工处置闭环；禁止把多个活动分配强行选成任意一个，禁止修改历史空间事件。

### 8.4 Outbox 死信或实时中断

数据库记录、投影和修正仍是事实源。检查 RabbitMQ、最小权限、交换机绑定、发布确认、租约和退避耗尽原因；前端应显示实时降级并通过 REST 回查。修复后按受审计恢复流程重新发布待办，禁止删除 Outbox、伪造 `PUBLISHED` 或重写业务序号。

### 8.5 关账遗留

在工作台逐项处理未终结作业和运行循环：正常完成、填写原因异常终止，或处理候选/人工修正。确认没有活动作业和循环后再以 `mine:shift:close` 关闭班次。禁止为消除告警直接把班次改成 `CLOSED`。

### 8.6 停止与恢复

- 关闭 `MINE_OPERATION_RUNTIME_RABBIT_ENABLED`：停止消费和自动识别；空间事件仍在 RabbitMQ 持久队列等待，恢复前必须记录停机窗口并评估积压。已进入管理库的运营队列也会停止后台处理。
- 保持运行消费、关闭 `MINE_OPERATION_OUTBOX_ENABLED`：运营事实继续提交，实时变更留在 Outbox 等待恢复；前端通过 REST 读取，恢复后有界补发。
- 需要完全停止新事实时，应先暂停/终止相关作业并停止上游设备或模拟流量，而不是删除 RabbitMQ 队列或数据库证据。

恢复顺序：数据库和 RabbitMQ 健康 → 核对账号/队列/交换机 → 启用 Outbox 并观察死信归零 → 启用运营消费 → 有界排空 → 对账记录、候选、统计和班次遗留 → 恢复正常流量。

任何情况下都禁止回滚 Flyway、清空业务表、重写业务序号、覆盖不可变事实或让人工操作绕过 API 审计。

## 9. 生产放行前仍需完成

- 真实矿区班次、跨午夜、节假日、交接班和关账规则确认。
- 真实调度优先级、抢单/改派、车辆车型、司机资质和冲突处理规则验收。
- 装载/卸载区域、停留阈值、允许迟到、步骤超时和误报/漏报现场标定。
- 地磅、载荷传感器或外部系统吨位来源、校准、对账和异常补录规则。
- 目标车辆/标签/区域数量、定位频率、并发作业、峰值事件、查询和积压恢复容量基线。
- RabbitMQ、mine-server、数据库、Redis、Gateway 和 Telemetry 的主备切换与故障注入。
- 班次、作业、队列、候选、事实、修正、投影、Outbox 和实时水位的备份恢复演练。
- 告警送达、值班升级、操作审计、弱网/时钟漂移及连续 24/72 小时长稳。

以上项目当前均为 `DEFERRED`。证据完成前，只能宣称“仓库自动化生产运营闭环通过”，不能宣称已经取得矿区生产上线资格。
