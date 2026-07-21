# Route, Waypoint and Geofence Event Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付矿区版本化路线、途径点、电子围栏及可迟到排序、消抖、重放幂等的进入/离开/停留事件闭环。

**Architecture:** 管理 PostgreSQL 保存矿区作用域内的草稿和不可变发布版本，通过现有定位运行快照下发到 Telemetry。Telemetry 使用数据库队列和事件时间水位排序位置事实，在同一事务维护空间状态、业务事件和可靠 Outbox；mine-server 提供管理/事件查询 API，mine-web 提供单一“路线与围栏”工作台。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis、PostgreSQL 18、TimescaleDB、RabbitMQ、Redis、Micrometer、Vue 3、TypeScript、Naive UI、UnoCSS、Vitest、Playwright、Prometheus。

---

### Task 1：固化设计、风险和执行门禁

**Files:**
- Create: `docs/plans/2026-07-18-route-geofence-event-design.md`
- Create: `docs/plans/2026-07-18-route-geofence-event-implementation.md`

**Step 1: 校验设计覆盖关键不变量**

Run: `rg -n "迟到|抖动|幂等|Outbox|DEFERRED|中文注释" docs/plans/2026-07-18-route-geofence-event-*.md`

Expected: 每项至少出现一次，且现场验证未混入仓库自动化结论。

**Step 2: 检查工作区并提交**

Run: `git diff --check`

Expected: PASS。

Commit: `docs(mine): 设计路线与围栏事件闭环`

### Task 2：建立途径点、路线和围栏版本数据库模型

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V39__create_spatial_route_control_plane.sql`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/SpatialRouteConstraintIT.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Step 1: 写失败的迁移契约测试**

测试必须查询 `information_schema` 和 PostgreSQL 注释函数，证明八张表及每个字段都有中文注释；插入跨矿区坐标系、重复编码、
重叠发布有效期、自相交/少于三点围栏、越界途径点、乱序路线点和修改已发布版本都失败。

Run: `./mvnw -pl mine-server -am -Dtest=SpatialRouteConstraintIT,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: RED，缺少 V39 表。

**Step 2: 实现最小迁移**

创建稳定对象和版本/子项表，核心状态约束为：

```sql
CHECK (status IN ('DRAFT', 'PUBLISHED'));
EXCLUDE USING gist (
  waypoint_id WITH =,
  tstzrange(valid_from, valid_to, '[)') WITH &&
) WHERE (status = 'PUBLISHED');
```

发布版本及其子项通过触发器禁止 UPDATE/DELETE；几何顶点使用有序独立行，服务端和数据库触发器共同校验范围、闭合语义和引用。
所有表/字段补齐 `COMMENT ON TABLE/COLUMN`。

**Step 3: 运行测试并提交**

Run: `./mvnw -pl mine-server -am -Dtest=SpatialRouteConstraintIT,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: GREEN。

Commit: `feat(mine): 建立路线与围栏版本模型`

### Task 3：实现确定性空间几何内核

**Files:**
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/spatial/SpatialPoint.java`
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/spatial/SpatialZone.java`
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/spatial/SpatialClassification.java`
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/spatial/SpatialClassifier.java`
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/spatial/package-info.java`
- Create: `mine-device-model/src/test/java/com/bosco/mine/model/spatial/SpatialClassifierTest.java`

**Step 1: 写失败的几何测试**

使用真实对象覆盖圆形、多边形、凹多边形、顶点/边界容差、高度、缺少 Z、非有限数字和自相交拒绝。期望 API：

```java
SpatialClassification result = classifier.classify(zone, point);
assertThat(result).isEqualTo(SpatialClassification.INSIDE);
```

Run: `./mvnw -pl mine-device-model -Dtest=SpatialClassifierTest test`

Expected: RED，空间类型尚不存在。

**Step 2: 实现最小纯 Java 内核**

使用点到线段距离优先判定 `BOUNDARY`，再使用确定性射线法判定多边形；圆形使用平方距离；高度范围在二维分类后判定。公开枚举只允许
`INSIDE`、`OUTSIDE`、`BOUNDARY`、`HEIGHT_UNKNOWN`。

**Step 3: 运行测试并提交**

Run: `./mvnw -pl mine-device-model -Dtest=SpatialClassifierTest test`

Expected: GREEN。

Commit: `feat(spatial): 增加确定性空间几何内核`

### Task 4：实现空间主数据 Mapper、DTO 和矿区作用域服务

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/model/*.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/dto/*.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/SpatialRouteMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/SpatialRouteQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/SpatialRouteCommandService.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/SpatialRouteManagementIT.java`

**Step 1: 写失败的管理集成测试**

覆盖创建稳定编码、草稿版本、乐观修改、分页、详情、跨矿拒绝、统一坐标系要求、发布摘要、发布引用、归档和不可变历史。

Run: `./mvnw -pl mine-server -am -Dtest=SpatialRouteManagementIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: RED，服务尚不存在。

**Step 2: 实现查询与命令服务**

所有写操作先通过 `MineScopeAuthorizer.requireSite` 获取服务端组织/矿区；草稿更新必须带 `version`；发布规范化排序子项并计算
`sha256:` 摘要，随后触发 `LocationSnapshotService.captureSite(..., SPATIAL_CHANGE)`。

**Step 3: 运行测试并提交**

Run: `./mvnw -pl mine-server -am -Dtest=SpatialRouteManagementIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: GREEN。

Commit: `feat(mine): 实现路线与围栏管理服务`

### Task 5：发布精确 RBAC、REST 和 OpenAPI 契约

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/SpatialRouteController.java`
- Create: `mine-server/src/main/resources/db/migration/V40__publish_spatial_route_access.sql`
- Modify: `docs/openapi/mine-api.yaml`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/controller/MineManagementOpenApiTest.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/bootstrap/security/PermissionIntegrationIT.java`

**Step 1: 写失败的控制器、权限和契约测试**

固定权限：`mine:spatial:view/edit/publish`、`mine:spatial:event:view`、`mine:spatial:replay`。覆盖参数上限、枚举、错误码、发布版本、
重放幂等头和跨矿区数据范围。

**Step 2: 实现接口并更新 OpenAPI**

集合路径固定为 `/waypoints`、`/routes`、`/geofences`；版本操作使用 `/{id}/versions` 和
`/{id}/versions/{versionId}/publish`，禁止通用 JSON 任意更新。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -am -Dtest=MineManagementOpenApiTest,PermissionIntegrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: GREEN。

Commit: `feat(mine): 发布路线与围栏管理契约`

### Task 6：扩展定位运行快照承载已发布空间版本

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V41__extend_location_snapshot_for_spatial.sql`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/location/LocationSnapshotMapper.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/location/LocationSnapshotService.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/LocationSnapshotPublishingIT.java`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/location/snapshot/LocationSnapshotLoaderIT.java`

**Step 1: 写失败的快照测试**

证明快照只含发生时可用的 `PUBLISHED` 版本，路线引用完整，几何顶点顺序稳定，摘要对等价输入稳定；非法新快照不能替换 last-known-good。

**Step 2: 实现快照条目**

新增 `WAYPOINT_VERSION`、`ROUTE_VERSION`、`GEOFENCE_VERSION` 和 `SPATIAL_CHANGE`，内容只含运行所需非敏感字段。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server,mine-telemetry -am -Dtest=LocationSnapshotPublishingIT,LocationSnapshotLoaderIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: GREEN。

Commit: `feat(location): 下发路线与围栏运行快照`

### Task 7：建立空间评估队列、状态、事件、重放和 Outbox 表

**Files:**
- Create: `mine-telemetry/src/main/resources/db/migration/V25__create_spatial_event_runtime.sql`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryMigrationIT.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/spatial/SpatialRuntimeConstraintIT.java`

**Step 1: 写失败的迁移测试**

校验所有表/字段中文注释、固定枚举、租约一致性、事件唯一键、状态外键、证据大小、Outbox 状态和重放时间窗。

**Step 2: 实现 V25**

创建 `spatial_evaluation_queue`、`spatial_subject_watermark`、`spatial_zone_state`、`spatial_event`、
`spatial_event_outbox`、`spatial_replay_request`，所有数据带组织和矿区标识并建立查询/领取索引。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-telemetry -am -Dtest=TelemetryMigrationIT,SpatialRuntimeConstraintIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: GREEN。

Commit: `feat(spatial): 建立空间事件运行存储`

### Task 8：实现事件时间水位和确定性迟到排序

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/spatial/SpatialEvaluationQueue.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/spatial/SpatialWatermarkPolicy.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/spatial/SpatialWatermarkPolicyTest.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/spatial/SpatialEvaluationQueueIT.java`

**Step 1: 写失败测试**

覆盖容忍窗内乱序排序、超过窗口固定跳过、同时间按 `ingestSequence`、空闲刷新、重复入队、租约超时和失败退避。

**Step 2: 实现最小水位和领取逻辑**

只处理 `occurredAt <= maxOccurredAt - allowedLateness`，空闲超时后处理剩余记录；领取使用 `FOR UPDATE SKIP LOCKED`。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-telemetry -am -Dtest=SpatialWatermarkPolicyTest,SpatialEvaluationQueueIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: GREEN。

Commit: `feat(spatial): 实现定位事件时间排序`

### Task 9：实现进入、离开和停留状态机

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/spatial/SpatialEventStateMachine.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/spatial/SpatialEventStateMachineTest.java`

**Step 1: 写失败状态机测试**

覆盖连续确认点、边界保持、低质量跳过、高度缺失、进入、单次停留、离开后重入、配置版本切换和无绑定标签。

**Step 2: 实现纯状态转换**

期望接口：

```java
TransitionResult result = machine.evaluate(zone, priorState, locationFact);
```

纯函数不得访问数据库、时钟或网络；所有时间来自位置事实和已发布区域配置。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-telemetry -Dtest=SpatialEventStateMachineTest test`

Expected: GREEN。

Commit: `feat(spatial): 实现围栏事件状态机`

### Task 10：打通位置事实、空间事件与可靠 Outbox 事务

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/spatial/SpatialEvaluationWorker.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/spatial/SpatialEventOutboxRelay.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/LocationFactWriter.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/bootstrap/TelemetryStorageConfiguration.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/spatial/SpatialEventPipelineIT.java`

**Step 1: 写失败纵向集成测试**

真实 TimescaleDB/RabbitMQ 覆盖位置入队、排序、状态/事件/Outbox 同事务、重复重放、事务回滚、发布确认、退避和 DEAD。

**Step 2: 实现运行组件**

新位置事实只负责幂等入队；Worker 读取激活快照并在单事务内推进状态和事件，Outbox Relay 只发布
`realtime.spatial.event.created` v1。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-telemetry -am -Dtest=SpatialEventPipelineIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: GREEN。

Commit: `feat(spatial): 打通可靠空间事件流水线`

### Task 11：实现事件查询、显式重放和实时订阅

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/SpatialEventController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/SpatialEventQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/spatial/SpatialReplayService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeEventConsumer.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/SpatialEventQueryIT.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/SpatialReplayIT.java`

**Step 1: 写失败测试**

覆盖矿区作用域、24 小时默认/31 天上限、固定水位游标、区域/对象/事件类型过滤、证据详情、重放幂等键、并发冲突和实时白名单。

**Step 2: 实现 API 和实时事件**

重放只创建请求，不在 HTTP 线程同步扫描历史；权限撤销后 WebSocket 下一次动作立即失效。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -am -Dtest=SpatialEventQueryIT,SpatialReplayIT,RealtimeWebSocketHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: GREEN。

Commit: `feat(mine): 提供空间事件查询与重放`

### Task 12：交付路线与围栏管理工作台

**Files:**
- Create: `mine-web/src/modules/mine/pages/spatial-management/SpatialManagementPage.vue`
- Create: `mine-web/src/modules/mine/pages/spatial-management/SpatialManagementPage.test.ts`
- Create: `mine-web/src/modules/mine/components/SpatialGeometryEditor.vue`
- Create: `mine-web/src/modules/mine/components/SpatialGeometryEditor.test.ts`
- Modify: `mine-web/src/modules/mine/api/mine-api.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Step 1: 写失败页面测试**

覆盖四页签、矿区切换、加载/空/错误、权限、草稿乐观冲突、几何校验、发布影响确认、历史只读、事件筛选和窄屏堆叠。

**Step 2: 实现 Naive UI 页面**

禁止页面 `<style>`、内联 CSS、硬编码颜色和用户可见硬编码文案；画布颜色从主题 token 派生。

**Step 3: 验证并提交**

Run: `pnpm --dir mine-web test:unit -- src/modules/mine/pages/spatial-management src/modules/mine/components/SpatialGeometryEditor.test.ts`

Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check`

Expected: GREEN。

Commit: `feat(frontend): 交付路线与围栏工作台`

### Task 13：叠加空间区域和实时事件

**Files:**
- Modify: `mine-web/src/modules/mine/pages/realtime-location/RealtimeLocationPage.vue`
- Modify: `mine-web/src/modules/mine/components/MineCoordinateCanvas.vue`
- Modify: `mine-web/src/modules/mine/composables/useMineLocationRealtime.ts`
- Create: `mine-web/src/modules/mine/composables/useMineSpatialEvents.ts`
- Create: `mine-web/src/modules/mine/composables/useMineSpatialEvents.test.ts`
- Create: `mine-web/e2e/mine-spatial-event.spec.ts`

**Step 1: 写失败交互和实时测试**

覆盖已发布区域叠加、区域开关、进入/离开/停留提示、sequence 断档重同步、质量/模拟标识、详情证据和 ECharts 生命周期。

**Step 2: 实现叠加与降级**

空间事件服务不可用时只降级区域/事件层，不影响当前位置、轨迹和资产管理。

**Step 3: 验证并提交**

Run: `pnpm --dir mine-web test:unit -- src/modules/mine`

Run: `pnpm --dir mine-web exec playwright test e2e/mine-spatial-event.spec.ts`

Expected: GREEN。

Commit: `feat(frontend): 展示实时空间事件`

### Task 14：增加指标、告警和确定性模拟场景

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/spatial/SpatialEventMetrics.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/spatial/SpatialEventMetricsTest.java`
- Modify: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/location/LocationRouteGenerator.java`
- Modify: `deploy/config/prometheus/rules/platform.yml`
- Modify: `deploy/config/prometheus/tests/platform.test.yml`
- Modify: `deploy/tests/prometheus-rules.sh`

**Step 1: 写失败指标和模拟测试**

新增跨边界、边界抖动、停留和迟到场景；指标标签只允许固定 result/reason/zoneType/eventType/stage。

**Step 2: 实现指标和告警**

告警覆盖空间队列积压、过晚率、Outbox DEAD、状态恢复失败和重放超时，禁止业务身份高基数标签。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-device-simulator-app,mine-telemetry -am -Dtest=SpatialEventMetricsTest,LocationRouteGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Run: `bash deploy/tests/prometheus-rules.sh`

Expected: GREEN。

Commit: `feat(spatial): 增加空间事件模拟与监控`

### Task 15：完成运行手册和自动化验收

**Files:**
- Create: `docs/runbooks/mine-spatial-event-runtime.md`
- Create: `docs/reports/phase-3-spatial-event-acceptance.md`
- Modify: `docs/06-mine-business.md`
- Modify: `docs/11-contracts-and-database.md`
- Modify: `docs/12-roadmap-and-codex-workflow.md`
- Modify: `docs/README.md`

**Step 1: 执行完整门禁**

Run: `./mvnw test && ./mvnw verify -DskipUnitTests`

Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`

Run: `bash deploy/tests/compose-config.sh && bash deploy/tests/prometheus-rules.sh`

Expected: 全部 PASS。

**Step 2: 记录自动化与真实验证边界**

报告分别列出仓库自动化证据和 `DEFERRED` 的真实道路/围栏测绘、定位误差、现场阈值、目标频率/容量、HA、备份恢复和长稳；
不得宣称已取得矿区生产放行资格。

**Step 3: 提交**

Commit: `docs(mine): 完成空间事件自动化验收`

## 批次检查点

1. **批次 A（Task 1～3）**：设计、关系模型和纯几何内核。
2. **批次 B（Task 4～6）**：管理 API、RBAC/OpenAPI 和运行快照。
3. **批次 C（Task 7～10）**：迟到排序、状态机、事件与可靠 Outbox。
4. **批次 D（Task 11～14）**：查询/重放、前端、实时叠加和可观测性。
5. **批次 E（Task 15）**：全量门禁、运行手册和仓库验收。

每个批次结束必须先审查差异和验证结果，再进入下一批；任何真实现场项都不允许被模拟或容器证据替代。
