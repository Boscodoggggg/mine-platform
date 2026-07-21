# Phase 2 双来源告警运行与处置闭环 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付数值阈值与数据质量双来源告警策略、Telemetry 持久运行状态机、可靠事件和 Server/Web 处置闭环。

**Architecture:** `ALARM.rules` 以原子集合参与三级有效值解析；控制面通过既有版本化策略快照分发到 Telemetry 最后正确镜像，Telemetry 在遥测事务内推进告警状态并写 Outbox，Server 通过 Inbox 幂等落库并提供确认/关闭 API，Web 提供策略编辑器与告警中心。

**Tech Stack:** Java 21、Spring Boot 4、PostgreSQL/TimescaleDB、RabbitMQ quorum queue、MyBatis/JdbcTemplate、Vue 3、TypeScript、Naive UI、UnoCSS、Vitest、Playwright、Testcontainers。

---

## 任务 1：注册原子规则集合与 `ALARM` 策略类型

**文件：**

- 修改：`mine-device-model/src/main/java/com/bosco/mine/model/strategy/EffectiveStrategyResolver.java`
- 新增：`mine-device-model/src/main/java/com/bosco/mine/model/strategy/AlarmStrategyDefinition.java`
- 新增：`mine-device-model/src/test/java/com/bosco/mine/model/strategy/AlarmStrategyDefinitionTest.java`
- 修改：`mine-device-model/src/test/java/com/bosco/mine/model/strategy/EffectiveStrategyResolverTest.java`
- 修改：`mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/StrategyConfigurationService.java`
- 修改：`mine-server/src/test/java/com/bosco/mine/server/modules/strategy/service/StrategyConfigurationServiceTest.java`
- 新增：`mine-server/src/main/resources/db/migration/V25__enable_alarm_strategy.sql`
- 修改：`mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

步骤：

1. 先写失败测试，覆盖递归规范化 JSON 值、稳定哈希、整组覆盖、同优先级不同规则集冲突和既有标量兼容。
2. 为有效策略解析器增加受限 `List/Map` 规范化与稳定摘要，不接受任意 Java 对象、非有限数字或空键。
3. 先写 `ALARM` 定义失败测试，覆盖双来源字段矩阵、未知字段、规则数、重复键、数值范围、迟滞方向和规范化。
4. 实现 `AlarmStrategyDefinition`，保证所有新增/修改 Java 类型与公开方法具有中文 Javadoc。
5. 把 `ALARM` 注册到服务端白名单，扩展策略版本/发布数据库约束并校验迁移注释。
6. 运行：`./mvnw -B -ntp -pl :mine-device-model,:mine-server -am -Dtest=AlarmStrategyDefinitionTest,EffectiveStrategyResolverTest,StrategyConfigurationServiceTest,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`。
7. 提交：`feat(strategy): 注册双来源告警策略`。

## 任务 2：把 `ALARM` 接入可靠运行快照

**文件：**

- 修改：`mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/StrategyRuntimeSnapshotService.java`
- 修改：`mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/DeviceGroupService.java`
- 修改：`mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceCommandService.java`
- 修改：`mine-server/src/main/resources/db/migration/V25__enable_alarm_strategy.sql`
- 修改：`mine-server/src/test/java/com/bosco/mine/server/modules/strategy/service/StrategyRuntimeSnapshotServiceTest.java`
- 修改：`mine-server/src/test/java/com/bosco/mine/server/modules/strategy/StrategyRuntimeSnapshotIT.java`
- 修改：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/strategy/StrategySnapshotIntegrity.java`
- 修改：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/strategy/StrategyPolicyRepository.java`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/AlarmPolicy.java`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/AlarmPolicyRepository.java`
- 新增：`mine-telemetry/src/main/resources/db/migration/V12__enable_alarm_strategy_mirror.sql`
- 修改：`mine-telemetry/src/test/java/com/bosco/mine/telemetry/strategy/StrategySnapshotIntegrityTest.java`
- 修改：`mine-telemetry/src/test/java/com/bosco/mine/telemetry/strategy/StrategyPolicyRepositoryIT.java`

步骤：

1. 先写失败测试，证明发布、回滚、成员、优先级、设备创建/归档都会生成 `ALARM` 修订，未发布类型不制造事件。
2. 将快照服务从单一数据质量定义改为受控类型注册表，仍保持产品/类型单调修订和空对象删除语义。
3. 先写 Telemetry 快照测试，覆盖类型独立水位、规则规范化、低修订拒绝、最后正确版本和原子激活。
4. 扩展镜像约束，新增 `telemetry_alarm_policy`；所有新表和字段使用中文注释，集成测试逐列校验。
5. 运行：`./mvnw -B -ntp -pl :mine-server,:mine-telemetry -am -Dtest=StrategyRuntimeSnapshotServiceTest,StrategyRuntimeSnapshotIT,StrategySnapshotIntegrityTest,StrategyPolicyRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false test`。
6. 提交：`feat(strategy): 分发告警策略运行快照`。

## 任务 3：实现告警消息契约与 Telemetry Outbox

**文件：**

- 新增：`mine-messaging/src/main/java/com/bosco/mine/messaging/alarm/AlarmRaisedPayload.java`
- 新增：`mine-messaging/src/main/java/com/bosco/mine/messaging/alarm/AlarmRecoveredPayload.java`
- 新增：`mine-messaging/src/main/java/com/bosco/mine/messaging/alarm/AlarmEvidencePayload.java`
- 修改：`mine-messaging/src/main/java/com/bosco/mine/messaging/topology/MineTopology.java`
- 新增：`mine-messaging/src/test/java/com/bosco/mine/messaging/alarm/AlarmPayloadTest.java`
- 新增：`mine-telemetry/src/main/resources/db/migration/V13__create_alarm_runtime_and_outbox.sql`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/outbox/TelemetryOutboxRepository.java`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/outbox/TelemetryOutboxRelay.java`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/outbox/RabbitAlarmOutboxPublisher.java`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/outbox/TelemetryOutboxRecovery.java`
- 新增：`mine-telemetry/src/test/java/com/bosco/mine/telemetry/alarm/outbox/TelemetryOutboxRelayTest.java`
- 新增：`mine-telemetry/src/test/java/com/bosco/mine/telemetry/alarm/AlarmOutboxIT.java`
- 修改：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/bootstrap/TelemetryStorageConfiguration.java`
- 修改：`mine-telemetry/src/main/resources/application.yml`

步骤：

1. 先写消息契约失败测试，固定 schema 版本、必填身份、有界文本、数值证据和 raised/recovered 完整自描述语义。
2. 新增 `telemetry_alarm_runtime_state` 与通用 `telemetry_outbox_event`，约束状态、事件类型、租约、重试和哈希；迁移测试校验中文注释完整。
3. 先写 relay 失败测试，覆盖精确认领、confirm 成功、指数退避、租约回收、未知事件拒绝和启动有界恢复。
4. 实现只允许 `alarm.raised`、`alarm.recovered` 的 RabbitMQ 发布器，日志与错误字段脱敏并截断。
5. 运行：`./mvnw -B -ntp -pl :mine-messaging,:mine-telemetry -am -Dtest=AlarmPayloadTest,TelemetryOutboxRelayTest,AlarmOutboxIT -Dsurefire.failIfNoSpecifiedTests=false test`。
6. 提交：`feat(messaging): 建立告警可靠事件出口`。

## 任务 4：实现持久告警评估状态机

**文件：**

- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/AlarmRule.java`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/AlarmRuleEvaluator.java`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/AlarmStateMachine.java`
- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/AlarmEvaluation.java`
- 新增：`mine-telemetry/src/test/java/com/bosco/mine/telemetry/alarm/AlarmRuleEvaluatorTest.java`
- 新增：`mine-telemetry/src/test/java/com/bosco/mine/telemetry/alarm/AlarmStateMachineTest.java`
- 修改：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/storage/TelemetryBatchWriter.java`
- 修改：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/consumer/NormalizedTelemetryConsumer.java`
- 修改：`mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryStorageIT.java`
- 修改：`mine-telemetry/src/test/java/com/bosco/mine/telemetry/consumer/NormalizedTelemetryConsumerTest.java`

步骤：

1. 先写纯状态机失败测试，覆盖 0 秒触发、持续触发、抖动回退、恢复持续、迟滞、冷却、缺值保持和质量规则命中。
2. 补乱序、旧 boot、重投、质量不可用、接收时间倒退和多个证据单次命中测试。
3. 实现纯规则评估和状态迁移，状态机不访问网络、不解释任意表达式。
4. 先写数据库集成失败测试，证明 Inbox、历史、质量证据、状态和 Outbox 同事务提交，回滚时全部不可见，重投不重复迁移。
5. 修改批写器，让告警只对持久最新顺序状态接受的帧执行；返回已提交事件供 relay 精确唤醒。
6. 规则删除、禁用或指纹变化时，快照激活事务生成带原因的恢复事件并关闭旧周期。
7. 运行：`./mvnw -B -ntp -pl :mine-telemetry -am verify`。
8. 提交：`feat(telemetry): 执行持久告警状态机`。

## 任务 5：实现 Server 告警事件和处置 API

**文件：**

- 新增：`mine-server/src/main/resources/db/migration/V26__create_alarm_center.sql`
- 新增：`mine-server/src/main/java/com/bosco/mine/server/modules/alarm/domain/AlarmStatus.java`
- 新增：`mine-server/src/main/java/com/bosco/mine/server/modules/alarm/repository/AlarmEventRepository.java`
- 新增：`mine-server/src/main/java/com/bosco/mine/server/modules/alarm/messaging/AlarmEventConsumer.java`
- 新增：`mine-server/src/main/java/com/bosco/mine/server/modules/alarm/service/AlarmEventService.java`
- 新增：`mine-server/src/main/java/com/bosco/mine/server/modules/alarm/controller/AlarmEventController.java`
- 新增：`mine-server/src/test/java/com/bosco/mine/server/modules/alarm/AlarmEventConsumerIT.java`
- 新增：`mine-server/src/test/java/com/bosco/mine/server/modules/alarm/service/AlarmEventServiceTest.java`
- 新增：`mine-server/src/test/java/com/bosco/mine/server/modules/alarm/controller/AlarmEventControllerTest.java`
- 修改：`mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`
- 修改：`docs/openapi/mine-api.yaml`

步骤：

1. 先写迁移失败测试，覆盖 `alarm_event`、`alarm_acknowledgement` 的中文注释、唯一键、状态事实约束、索引和外键。
2. 先写消费者失败测试，覆盖重复 eventId、重复 occurrenceId、恢复早到、raised 晚到、非法身份和事务失败 NACK。
3. 实现 `sys_inbox_event + alarm_event upsert` 单事务消费，恢复事件携带完整身份，不依赖先到 raised。
4. 先写服务/API 失败测试，覆盖分页筛选、详情、确认幂等、已确认重复请求、未恢复关闭拒绝、关闭幂等、乐观并发、权限和审计。
5. 实现正交时间事实和派生状态，确认/关闭说明做长度与空白校验。
6. 更新 OpenAPI 并运行生成客户端契约校验。
7. 运行：`./mvnw -B -ntp -pl :mine-server -am verify`。
8. 提交：`feat(alarm): 建立告警处置闭环`。

## 任务 6：配置 RabbitMQ 拓扑、权限与可观测性

**文件：**

- 修改：`deploy/config/rabbitmq/definitions.json`
- 修改：`deploy/scripts/rabbitmq-bootstrap.sh`
- 修改：`deploy/compose/compose-standalone.yml`
- 修改：`deploy/config/prometheus/rules/mine-platform.yml`
- 修改：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/alarm/AlarmMetrics.java`
- 新增：`mine-server/src/main/java/com/bosco/mine/server/modules/alarm/AlarmMetrics.java`
- 修改：相关 RabbitMQ 配置测试、Prometheus 规则测试和部署脚本测试

步骤：

1. 先写失败测试，要求 `mine.business.x`、quorum 告警队列、死信、持久消息、publisher confirm、手工 ACK 和最小账号权限。
2. Telemetry 账号只增加写业务交换机权限；Server 账号只增加读告警队列权限，不扩大到无关资源。
3. 增加低基数指标和 Outbox 堆积、消费失败、严重活动告警增长规则，标签不得含设备、规则或告警标识。
4. 运行部署配置校验、Prometheus 规则校验和 RabbitMQ 容器集成测试。
5. 提交：`feat(deploy): 接入告警消息拓扑与监控`。

## 任务 7：实现告警策略编辑器和告警中心

**文件：**

- 修改：`mine-web/src/modules/device/pages/strategy/StrategyWorkbenchPage.vue`
- 修改：`mine-web/src/modules/device/pages/strategy/StrategyWorkbenchPage.test.ts`
- 新增：`mine-web/src/modules/device/components/strategy/AlarmRuleEditor.vue`
- 新增：`mine-web/src/modules/device/components/strategy/AlarmRuleEditor.test.ts`
- 新增：`mine-web/src/modules/alarm/api/alarm.ts`
- 新增：`mine-web/src/modules/alarm/api/alarm.test.ts`
- 新增：`mine-web/src/modules/alarm/pages/AlarmCenterPage.vue`
- 新增：`mine-web/src/modules/alarm/pages/AlarmCenterPage.test.ts`
- 修改：`mine-web/src/router/component-registry.ts`
- 修改：`mine-web/src/locales/langs/zh-cn.ts`
- 修改：`mine-web/src/locales/langs/en-us.ts`
- 修改：`mine-server/src/main/resources/db/migration/V26__create_alarm_center.sql`
- 修改：`mine-web/e2e/production.spec.ts`

步骤：

1. 先写编辑器失败测试，覆盖来源切换、字段矩阵、迟滞提示、完整规则集提交、只读来源和中英文。
2. 使用 Naive UI 与 UnoCSS 实现规则编辑器，不写 `<style>`、内联 CSS、硬编码颜色或页面私有样式。
3. 先写告警中心失败测试，覆盖默认待处置筛选、等级/状态/设备过滤、详情、确认、恢复后关闭、权限隐藏和错误态。
4. 增加后端菜单/按钮权限种子、前端组件注册和全部中英文文案。
5. 生成客户端并运行：`pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`。
6. 运行 Chromium E2E，覆盖策略发布、raised 展示、确认、recovered 和关闭。
7. 提交：`feat(frontend): 交付告警策略与处置中心`。

## 任务 8：文档、全仓验收与收口

**文件：**

- 修改：`docs/04-device-model-strategy.md`
- 修改：`docs/05-messaging-telemetry-data.md`
- 修改：`docs/06-mine-business.md`
- 修改：`docs/07-frontend.md`
- 修改：`docs/08-security-auth-audit.md`
- 修改：`docs/10-testing-quality-gates.md`
- 修改：`docs/11-contracts-and-database.md`
- 修改：`docs/12-roadmap-and-codex-workflow.md`
- 新增：`docs/runbooks/alarm-runtime.md`
- 新增：`docs/reports/phase-2-alarm-runtime-acceptance.md`
- 修改：`docs/README.md`

步骤：

1. 补充策略、状态机、消息、表、API、权限、审计、失败语义和运行排障文档。
2. 运行定向 Maven、前端治理、单元、构建、RabbitMQ/数据库容器集成、Prometheus/Loki/Alloy、SBOM 和离线构建检查。
3. 运行：`bash scripts/verify.sh`，再运行仓库规定的 Chromium E2E。
4. 按 `requesting-code-review` skill 做最终代码审查；修复高/中风险问题后重跑受影响门禁。
5. 验收报告明确区分“非现场自动化通过”和“生产放行未完成”，列出真实设备、通知送达、容量、HA、备份恢复和长稳延期项。
6. 提交：`docs(alarm): 完善告警运行验收与运维说明`。
