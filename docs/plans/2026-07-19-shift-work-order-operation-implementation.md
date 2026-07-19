# Shift, Work Order and Haulage Operation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付可扩展的班次与作业单内核，并用装载—运输—卸载模板形成可自动识别、人工修正、可靠实时和统计闭环的一趟运输事实。

**Architecture:** 管理 PostgreSQL 保存班次、作业单、分配、追加式执行/修正和有效投影。mine-server 使用独立持久 RabbitMQ 队列接收空间事件，经过 Inbox、事件排序和纯运输状态机后，在同一业务事务写状态、候选/记录、审计和专用 Outbox；前端使用单一“生产运营”工作台。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis、PostgreSQL 18、RabbitMQ、Micrometer、Vue 3、TypeScript、Naive UI、UnoCSS、Vitest、Playwright、Prometheus。

---

### Task 1：实现纯班次、作业单和运输循环状态机

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/domain/ShiftLifecycle.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/domain/WorkOrderLifecycle.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/domain/HaulCycleStateMachine.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/domain/package-info.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/operation/domain/ShiftLifecycleTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/operation/domain/WorkOrderLifecycleTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/operation/domain/HaulCycleStateMachineTest.java`

**Step 1: Write the failing lifecycle tests**

固定接口和行为：

```java
assertThat(ShiftLifecycle.transition(PLANNED, OPEN)).isEqualTo(OPEN);
assertThatThrownBy(() -> ShiftLifecycle.transition(CLOSED, OPEN))
        .isInstanceOf(IllegalStateException.class);

assertThat(WorkOrderLifecycle.transition(RELEASED, START)).isEqualTo(RUNNING);
assertThatThrownBy(() -> WorkOrderLifecycle.transition(COMPLETED, PAUSE))
        .isInstanceOf(IllegalStateException.class);
```

运输测试覆盖 `WAITING_LOAD -> LOADING -> LOADED -> HAULING -> UNLOADING -> COMPLETED`，完成后显式创建下一循环初态；区域版本、车辆或事件类型不匹配返回固定候选原因，重复证据幂等，旧 `occurredAt + ingestSequence` 不让状态倒退。

**Step 2: Run tests to verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=ShiftLifecycleTest,WorkOrderLifecycleTest,HaulCycleStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，领域类型尚不存在。

**Step 3: Implement pure state machines**

所有输入使用不可变 record/enum；`HaulCycleStateMachine.evaluate(State, SpatialSignal)` 不访问数据库、时钟、Spring 或网络。全部公开后端类型和方法写中文 Javadoc。

**Step 4: Run tests to verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=ShiftLifecycleTest,WorkOrderLifecycleTest,HaulCycleStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/domain mine-server/src/test/java/com/bosco/mine/server/modules/mine/operation/domain
git commit -m "feat(operation): 建立班次与运输作业状态机"
```

### Task 2：建立班次、作业单和运营事实数据库模型

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V43__create_operation_control_plane.sql`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/OperationControlPlaneConstraintIT.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Step 1: Write failing migration contract tests**

测试必须证明下列十二张表存在且表/每个字段都有中文注释：

```text
mine_shift_definition
mine_shift_instance
mine_work_order
mine_work_order_assignment
mine_operation_spatial_event_queue
mine_operation_runtime_state
mine_operation_candidate
mine_operation_record
mine_operation_evidence
mine_operation_correction
mine_operation_effective_record
mine_operation_outbox
```

覆盖跨矿外键、班次时长、营业日期唯一、作业窗口、固定状态、发布快照完整、车辆/司机分配排斥、事件队列租约、执行事实不可变、证据角色唯一、修正版本、Outbox 白名单/租约和 JSONB 大小上限。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=OperationControlPlaneConstraintIT,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，V43 表不存在。

**Step 3: Implement V43**

使用 `tstzrange` 与 GiST 排斥约束保护活动车辆/司机分配；已关闭班次、已发布作业快照、执行记录、证据和修正事实使用触发器禁止 UPDATE/DELETE。核心查询和 `PENDING/FAILED` 领取必须有部分索引。所有表和字段使用 `COMMENT ON TABLE/COLUMN`。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=OperationControlPlaneConstraintIT,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/resources/db/migration/V43__create_operation_control_plane.sql mine-server/src/test/java/com/bosco/mine/server/integration
git commit -m "feat(operation): 建立班次与作业运营模型"
```

### Task 3：实现班次模板与班次实例服务

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/OperationMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/ShiftQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/ShiftCommandService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/ShiftDefinitionRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/ShiftDefinitionResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/ShiftInstanceRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/ShiftInstanceResponse.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/ShiftManagementIT.java`

**Step 1: Write failing service integration tests**

覆盖矿区时区换算、跨午夜、模板有效期、同营业日期唯一实例、乐观修改/归档、开启/关闭/取消、关账遗留运行状态拒绝、跨矿区拒绝和关键动作幂等。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=ShiftManagementIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，班次服务不存在。

**Step 3: Implement query and command services**

所有入口先调用 `MineScopeAuthorizer.requireSite`。实例计划 UTC 边界只能由服务端使用矿区 `ZoneId` 和营业日期计算；关闭在事务内锁定班次及其运行作业/循环并写审计。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=ShiftManagementIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation mine-server/src/test/java/com/bosco/mine/server/integration/ShiftManagementIT.java
git commit -m "feat(operation): 实现矿区班次管理"
```

### Task 4：实现运输作业单、分配与发布冻结

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/WorkOrderQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/WorkOrderCommandService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/WorkOrderRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/WorkOrderResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/WorkOrderAssignmentRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/WorkOrderActionRequest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/WorkOrderManagementIT.java`

**Step 1: Write failing work-order tests**

覆盖矿区内单号、`HAULAGE`、草稿乐观修改、多车分配、车辆/司机时间排斥、发生时任职与车型资质、路线/装卸区域必须为同矿区发布版本、发布冻结摘要、全部合法/非法状态动作、幂等重放和跨矿拒绝。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=WorkOrderManagementIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，作业单服务不存在。

**Step 3: Implement minimal HAULAGE aggregate**

发布时读取并规范化路线、装载/卸载区域、计划、车辆和司机显示快照，计算 `sha256:` 摘要。发布后计划字段不可修改；运行中调整使用新增分配事实。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=WorkOrderManagementIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation mine-server/src/test/java/com/bosco/mine/server/integration/WorkOrderManagementIT.java
git commit -m "feat(operation): 实现运输作业单与调度分配"
```

### Task 5：发布生产运营 API、精确 RBAC 和 OpenAPI

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/ShiftController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/WorkOrderController.java`
- Create: `mine-server/src/main/resources/db/migration/V44__publish_operation_access.sql`
- Modify: `docs/openapi/mine-api.yaml`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/controller/MineManagementOpenApiTest.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/bootstrap/security/PermissionIntegrationIT.java`

**Step 1: Write failing controller, permission and contract tests**

固定权限：`mine:operation:view`、`mine:shift:edit`、`mine:work-order:edit`、`mine:work-order:dispatch`、`mine:operation:correct`、`mine:shift:close`。覆盖列表上限、枚举、矿区范围、动作幂等头、乐观版本、错误码和菜单依赖。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=MineManagementOpenApiTest,PermissionIntegrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，新 API 和 V44 权限不存在。

**Step 3: Implement controllers, V44 and OpenAPI**

动作路径使用 `/open|close|cancel`、`/release|start|pause|resume|complete|abort`，禁止 `PATCH status`。菜单固定为“矿区管理 / 生产运营”，BUTTON 不生成路由。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=MineManagementOpenApiTest,PermissionIntegrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server docs/openapi/mine-api.yaml
git commit -m "feat(operation): 发布生产运营管理契约"
```

### Task 6：持久接收并排序空间业务事件

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/messaging/OperationSpatialEventConsumer.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/runtime/OperationEventQueue.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/runtime/OperationRuntimeConfiguration.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/messaging/package-info.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/runtime/package-info.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/operation/messaging/OperationSpatialEventConsumerTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/OperationEventQueueIT.java`

**Step 1: Write failing durable-consumption tests**

覆盖独立 durable queue、手工 ACK、固定 event/schema 白名单、`sys_inbox_event` 防重、规范化 payload、`occurredAt + ingestSequence` 有界排序、同车串行、随机租约、旧 token 拒绝、失败退避、重启恢复和超过最终前沿的 `TOO_LATE`。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=OperationSpatialEventConsumerTest,OperationEventQueueIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，持久消费者和队列不存在。

**Step 3: Implement queue and consumer**

绑定现有 `mine.realtime.x` 的 `realtime.spatial.#`，使用独立队列/消费者名；WebSocket 临时队列保持不变。消息事务提交后 ACK，任何身份冲突固定拒绝且不泄露 payload。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=OperationSpatialEventConsumerTest,OperationEventQueueIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation mine-server/src/test/java/com/bosco/mine/server/modules/mine/operation mine-server/src/test/java/com/bosco/mine/server/integration/OperationEventQueueIT.java
git commit -m "feat(operation): 持久接收空间作业事件"
```

### Task 7：自动推进运输循环并形成执行事实

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/runtime/OperationAutomationWorker.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/runtime/OperationRuntimeRepository.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/runtime/OperationRuntimeCoordinator.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/HaulageOperationAutomationIT.java`

**Step 1: Write failing end-to-end automation tests**

用真实 PostgreSQL 数据覆盖唯一班次/作业/分配自动匹配、完整装卸循环、一趟原子记录/证据/有效投影、重复事件、乱序窗口、迟到候选、暂停、歧义、多矿隔离、步骤超时和重启状态恢复。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=HaulageOperationAutomationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，自动化 worker 不存在。

**Step 3: Implement transactional worker**

按作业分配行锁串行推进。只有唯一匹配且信号符合冻结区域版本时调用纯状态机；候选和正常转换都与队列完成处于同一事务。一趟完成写 `mine_operation_record/evidence/effective_record/outbox` 后重置新循环。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=HaulageOperationAutomationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/runtime mine-server/src/test/java/com/bosco/mine/server/integration/HaulageOperationAutomationIT.java
git commit -m "feat(operation): 自动形成运输作业记录"
```

### Task 8：实现候选处置、补录、修正和作废

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/OperationCorrectionService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/OperationQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/OperationController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/OperationCandidateResolutionRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/OperationCorrectionRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/OperationRecordResponse.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/OperationCorrectionIT.java`

**Step 1: Write failing correction tests**

覆盖候选关联/忽略/补录，`ADD/AMEND/VOID`，必填原因，原/新完整快照，基础事实不变，修正版本递增，有效投影原子更新，幂等同键重放/异请求冲突，权限和关账后追溯。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=OperationCorrectionIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，修正服务不存在。

**Step 3: Implement append-only correction flow**

客户端只提交允许修改的结构化字段和当前投影版本，服务端重建规范化 before/after 快照。任何修正同时写审计和运营 Outbox；禁止 UPDATE/DELETE 基础记录和修正表。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=OperationCorrectionIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation mine-server/src/test/java/com/bosco/mine/server/integration/OperationCorrectionIT.java
git commit -m "feat(operation): 支持作业候选与人工修正"
```

### Task 9：发布可靠运营实时事件

**Files:**
- Create: `mine-messaging/src/main/java/com/bosco/mine/messaging/realtime/RealtimeOperationEvent.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/outbox/OperationOutboxRelay.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeOperationConsumer.java`
- Modify: `mine-messaging/src/main/java/com/bosco/mine/messaging/topology/MineTopology.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeEventConsumer.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeSessionRegistry.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeWebSocketHandler.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/operation/outbox/OperationOutboxRelayTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/bootstrap/websocket/RealtimeOperationConsumerTest.java`

**Step 1: Write failing message, relay and WebSocket tests**

固定 `realtime.operation.updated` v1 和 `MINE_SITE_OPERATION` 主题。覆盖 UUIDv7、作用域、更新类型、业务序号、有界摘要、发布确认、随机租约、退避、DEAD、重启恢复、权限和矿区范围。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=OperationOutboxRelayTest,RealtimeOperationConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，消息和 relay 不存在。

**Step 3: Implement reliable publication**

专用 routing prefix 为 `realtime.operation.`，每个 server 实例继续使用独占临时实时队列；业务生产端使用持久 Outbox，不从浏览器状态反推。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=OperationOutboxRelayTest,RealtimeOperationConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-messaging mine-server
git commit -m "feat(operation): 可靠发布运营实时事件"
```

### Task 10：实现执行记录和运营统计查询

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/OperationSummaryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/OperationSummaryResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/dto/OperationRecordPageResponse.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/OperationSummaryQueryIT.java`

**Step 1: Write failing query tests**

覆盖矿区/营业日期、最长 31 天、固定水位分页、计划/有效完成趟数、完成率、吨位 unknown 与 0、阶段时长、异常、作废排除、最新修正投影、车辆利用率、司机作业量和跨午夜归集。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=OperationSummaryQueryIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，查询服务不存在。

**Step 3: Implement bounded SQL projections**

只读取 `mine_operation_effective_record` 和关系化计划/分配字段；列表使用数据库业务序号固定水位和不透明游标，统计查询要求明确窗口并命中作用域/时间索引。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=OperationSummaryQueryIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation mine-server/src/test/java/com/bosco/mine/server/integration/OperationSummaryQueryIT.java docs/openapi/mine-api.yaml
git commit -m "feat(operation): 提供作业记录与运营统计"
```

### Task 11：交付生产运营前端工作台

**Files:**
- Create: `mine-web/src/modules/mine/pages/production-operations/ProductionOperationsPage.vue`
- Create: `mine-web/src/modules/mine/pages/production-operations/ProductionOperationsPage.test.ts`
- Create: `mine-web/src/modules/mine/api/operation.ts`
- Modify: `mine-web/src/router/routes/index.ts`
- Modify: `mine-web/src/router/routes/components.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/modules/mine/components/MineSiteContextBar.vue`

**Step 1: Write failing component tests**

覆盖班次/作业单/待确认/执行记录/运营看板页签，加载/空/错、矿区切换、权限只读、状态动作、高风险确认、冻结版本、证据链、修正历史、unknown 吨位、中英文和窄屏。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web vitest run src/modules/mine/pages/production-operations/ProductionOperationsPage.test.ts`

Expected: FAIL，页面和 API 不存在。

**Step 3: Implement Naive UI workbench**

只使用 Naive UI、UnoCSS、主题 token 和 Vue I18n；禁止页面 `<style>`、内联 CSS、硬编码颜色与可见文案。路由组件键固定为 `mine-production-operations`。

**Step 4: Verify frontend gates**

Run: `pnpm --dir mine-web check:ui-governance`

Run: `pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`

Expected: 全部 PASS。

**Step 5: Commit**

```bash
git add mine-web
git commit -m "feat(frontend): 交付生产运营工作台"
```

### Task 12：接入运营实时增量和缺口恢复

**Files:**
- Modify: `mine-web/src/realtime/RealtimeHub.ts`
- Modify: `mine-web/src/realtime/RealtimeHub.test.ts`
- Modify: `mine-web/src/modules/mine/pages/production-operations/ProductionOperationsPage.vue`
- Modify: `mine-web/src/modules/mine/pages/production-operations/ProductionOperationsPage.test.ts`
- Test: `mine-web/e2e/mine-production-operations.spec.ts`

**Step 1: Write failing realtime and E2E tests**

测试 `MINE_SITE_OPERATION` 订阅、矿区切换释放、消息去重、业务序号缺口、REST 重同步、运营实时独立降级和断线重连；E2E 覆盖一张运行作业单收到完成趟次后列表/看板刷新。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web vitest run src/realtime/RealtimeHub.test.ts src/modules/mine/pages/production-operations/ProductionOperationsPage.test.ts`

Expected: FAIL，新主题未处理。

**Step 3: Implement realtime integration**

首次进入先读 REST，再订阅增量；发现缺口只重同步运营数据，不连带判死定位或空间事件主题。

**Step 4: Verify GREEN**

Run: `pnpm --dir mine-web vitest run src/realtime/RealtimeHub.test.ts src/modules/mine/pages/production-operations/ProductionOperationsPage.test.ts`

Run: `pnpm --dir mine-web exec playwright test e2e/mine-production-operations.spec.ts`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-web
git commit -m "feat(frontend): 打通运营实时增量"
```

### Task 13：增加确定性运输循环模拟

**Files:**
- Modify: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/location/LocationRouteGenerator.java`
- Modify: `mine-device-simulator-app/src/main/resources/static/index.html`
- Modify: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/location/LocationRouteGeneratorTest.java`
- Modify: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/web/SimulatorWebAppTest.java`

**Step 1: Write failing `HAUL_CYCLE` tests**

固定路线在装载区停留、离开、运输、卸载区停留并返回，坐标/速度/航向/时间/序号确定；另覆盖跳过装载停留和重复卸载异常变体。测试说明只有发布区域与固定几何一致时才产生预期作业。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-device-simulator-app -Dtest=LocationRouteGeneratorTest,SimulatorWebAppTest test`

Expected: FAIL，场景不存在。

**Step 3: Implement deterministic scenarios**

沿用固定种子和 boot 后缀隔离，不放宽 Gateway 身份、定位协议、空间边界或作业匹配。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-device-simulator-app -Dtest=LocationRouteGeneratorTest,SimulatorWebAppTest test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-device-simulator-app
git commit -m "feat(simulator): 增加确定性运输作业循环"
```

### Task 14：增加运营指标、告警和部署契约

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/operation/runtime/OperationMetrics.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/operation/runtime/OperationMetricsTest.java`
- Modify: `mine-server/src/main/resources/application.yml`
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/config/prometheus/rules/platform.yml`
- Modify: `deploy/config/prometheus/tests/platform.test.yml`
- Modify: `deploy/tests/prometheus-rules.sh`
- Modify: `deploy/tests/compose-config.sh`

**Step 1: Write failing metric and deployment tests**

固定指标覆盖队列积压/过晚、状态推进、候选、Outbox DEAD 和关账遗留；标签只允许 `stage/result/reason/workType/candidateType`。PromQL 测试包含阈值下、阈值上、最小样本和 `for` 行为。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=OperationMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `bash deploy/tests/compose-config.sh && bash deploy/tests/prometheus-rules.sh`

Expected: FAIL，指标、开关和告警尚不存在。

**Step 3: Implement observability**

Standalone 显式启用持久消费者和运营 Outbox；生产默认值与关闭影响写入配置说明。指标异常不能回滚业务事务。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=OperationMetricsTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `bash deploy/tests/compose-config.sh && bash deploy/tests/prometheus-rules.sh`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server deploy
git commit -m "feat(operation): 增加生产运营运行监控"
```

### Task 15：完成运行手册、文档和自动化验收

**Files:**
- Create: `docs/runbooks/mine-production-operation-runtime.md`
- Create: `docs/reports/phase-3-production-operation-acceptance.md`
- Modify: `docs/06-mine-business.md`
- Modify: `docs/07-frontend.md`
- Modify: `docs/11-contracts-and-database.md`
- Modify: `docs/12-roadmap-and-codex-workflow.md`
- Modify: `docs/README.md`

**Step 1: Run full gates**

Run: `./mvnw -B -ntp test && ./mvnw -B -ntp verify -DskipUnitTests`

Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`

Run: `bash deploy/tests/compose-config.sh && bash deploy/tests/prometheus-rules.sh`

Expected: 全部 PASS。

**Step 2: Write runtime and acceptance evidence**

手册覆盖班次配置、作业发布、分配、自动识别、候选处置、修正/作废、统计核查、指标、告警、安全停用和恢复。报告记录精确自动化计数，并把真实班制、调度规则、装卸阈值、吨位来源、目标车队/频率、容量、HA、备份恢复、值班和长稳全部列为 `DEFERRED`。

**Step 3: Update product truth**

路线图把“班次、作业单与运营闭环”改为自动化通过，后续路线转向 Cesium 三维与二维降级；接口、数据库、前端信息架构和 README 不再描述该切片为待开发。

**Step 4: Review and commit**

Run: `git diff --check`

Expected: PASS，独立审查无 Critical/Important。

```bash
git add docs
git commit -m "docs(mine): 完成生产运营自动化验收"
```

## 批次检查点

1. **批次 A（Task 1～2）**：纯状态机与数据库不变量。
2. **批次 B（Task 3～5）**：班次/作业单服务、RBAC 和公开契约。
3. **批次 C（Task 6～10）**：持久事件、自动识别、修正、可靠实时和统计。
4. **批次 D（Task 11～14）**：前端、实时、模拟和可观测性。
5. **批次 E（Task 15）**：全量门禁、运行手册和仓库验收。

每个批次结束必须复核差异和验证结果；任何真实现场项都不允许被模拟器、容器或自动化证据替代。
