# Phase 2 受控数据保留策略运行闭环 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付可配置、可预览、可限速、可恢复和可审计的 `RETENTION` 数据保留策略纵向闭环。

**Architecture:** 三级策略控制面生成单设备不可变有效策略快照，Telemetry 原子加载类型化镜像，并通过数据库单实例锁和有界批次清理过期原始遥测与质量证据。mine-server 仅用只读 TimescaleDB 账号提供影响预览和执行历史，mine-web 在策略工作台完成配置、风险确认和运行观察。

**Tech Stack:** Java 25、Spring Boot 4、MyBatis、JDBC、PostgreSQL 17、TimescaleDB 2.x Apache 模式、Flyway、Micrometer、Vue 3、TypeScript、Naive UI、Vue I18n、Vitest、Playwright。

---

### Task 1: 固化策略契约与控制面白名单

**Files:**
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/strategy/RetentionStrategyDefinition.java`
- Create: `mine-device-model/src/test/java/com/bosco/mine/model/strategy/RetentionStrategyDefinitionTest.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/StrategyConfigurationService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/StrategyRuntimeSnapshotService.java`
- Create: `mine-server/src/main/resources/db/migration/V27__enable_retention_strategy.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/strategy/service/StrategyConfigurationServiceTest.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/strategy/service/StrategyRuntimeSnapshotServiceTest.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Steps:**
1. 先写失败测试，覆盖原子 `policy` 规范化、四个等级边界、未知字段、缺字段、非整数和证据天数短于原始天数。
2. 运行 `./mvnw -B -ntp -pl mine-device-model -Dtest=RetentionStrategyDefinitionTest test`，确认因类型不存在而失败。
3. 实现带中文 Javadoc 的受控定义，将嵌套对象复制为稳定不可变顺序。
4. 写控制面失败测试，证明 `RETENTION` 草稿和运行快照尚未注册。
5. 注册控制面定义，新增 V27 白名单迁移及数据库约束测试。
6. 运行模型和服务端策略定向测试，确认通过后提交 `feat(strategy): 注册受控数据保留策略`。

### Task 2: 原子分发最后正确保留策略镜像

**Files:**
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/strategy/StrategySnapshotIntegrity.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/strategy/StrategyPolicyRepository.java`
- Create: `mine-telemetry/src/main/resources/db/migration/V15__create_retention_policy_mirror.sql`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/strategy/StrategySnapshotLoaderTest.java`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/strategy/StrategyPolicyRepositoryIT.java`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryMigrationIT.java`

**Steps:**
1. 先写失败测试，要求完整性注册表接受 `RETENTION` 并拒绝损坏策略。
2. 运行策略加载器测试并确认因不支持类型失败。
3. 新增 V15：扩展运行修订白名单，创建带生成列的 `telemetry_retention_policy`，为表和全部字段添加中文注释。
4. 先写仓储集成测试，覆盖应用、删除、低修订和同修订异内容。
5. 将 `RETENTION` 加入受控表路由，保持现有快照暂存和原子切换协议不变。
6. 运行 Telemetry 策略与迁移测试，确认通过后提交 `feat(telemetry): 原子加载数据保留策略`。

### Task 3: 建立有界删除执行与持久审计

**Files:**
- Create: `mine-telemetry/src/main/resources/db/migration/V16__create_retention_execution.sql`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/retention/RetentionExecutionRepository.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/retention/RetentionExecutor.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/retention/RetentionExecutorTest.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/retention/RetentionExecutorIT.java`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryMigrationIT.java`

**Steps:**
1. 先写失败测试描述锁竞争、禁用跳过、平台最短留存、两个数据集独立天数、批次上限和失败恢复。
2. 运行 `RetentionExecutorTest` / `RetentionExecutorIT`，确认因实现和迁移不存在而失败。
3. 新增执行事实表、终态保护和全部中文注释。
4. 实现会话 advisory lock、数据库时间候选选择、有界删除、`SUCCEEDED/PARTIAL/FAILED` 收敛和超时运行恢复。
5. 集成测试证明保护边界表行数不变、错误批次可重复执行、双执行器只有一个工作。
6. 运行定向单元与 TimescaleDB 集成测试，确认通过后提交 `feat(telemetry): 执行有界数据保留任务`。

### Task 4: 调度、指标和部署门禁

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/retention/RetentionProperties.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/retention/RetentionMetrics.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/retention/RetentionScheduler.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/retention/RetentionConfiguration.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/retention/RetentionSchedulerTest.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/retention/RetentionConfigurationTest.java`
- Modify: `mine-telemetry/src/main/resources/application.yml`
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/config/prometheus/rules/platform.yml`
- Modify: `deploy/tests/compose-config.sh`

**Steps:**
1. 先写失败测试覆盖配置边界、固定延迟单线程调度、关闭停止、异常不终止后续调度和低基数指标。
2. 实现默认关闭、Compose 显式开启的 Retention 配置；批量大小、单轮批次数和间隔均有安全上限。
3. 新增连续失败、长时间无成功和持续未完成 Prometheus 规则。
4. 运行 Telemetry 定向测试和部署配置测试，确认通过后提交 `feat(deploy): 接入数据保留调度与监控`。

### Task 5: 提供影响预览和执行历史 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/repository/RetentionRuntimeRepository.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/RetentionRuntimeService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/controller/RetentionRuntimeController.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/strategy/service/RetentionRuntimeServiceTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/strategy/controller/RetentionRuntimeControllerTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/strategy/RetentionRuntimeRepositoryIT.java`
- Modify: `mine-server/src/main/resources/openapi/mine-openapi.yml`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/strategy/controller/StrategyConfigurationOpenApiTest.java`

**Steps:**
1. 先写服务和 Controller 失败测试，覆盖作用域校验、策略复用校验、统计上限、不可用遥测查询、权限和响应契约。
2. 实现只读仓储，使用受影响设备分批查询两个数据集，返回统计时点、最早时间、数量和 `atLeast`。
3. 查询最近保留执行事实，不向业务库复制运行状态。
4. 更新 OpenAPI 并运行生成契约校验。
5. 运行服务端定向和容器集成测试，确认通过后提交 `feat(retention): 提供保留预览与执行查询`。

### Task 6: 交付保留策略工作台体验

**Files:**
- Create: `mine-web/src/modules/device/components/strategy/RetentionPolicyEditor.vue`
- Create: `mine-web/src/modules/device/components/strategy/RetentionPolicyEditor.test.ts`
- Modify: `mine-web/src/modules/device/pages/strategy/StrategyWorkbenchPage.vue`
- Modify: `mine-web/src/modules/device/pages/strategy/StrategyWorkbenchPage.test.ts`
- Modify: `mine-web/src/modules/device/components/strategy/EffectiveStrategyPanel.vue`
- Modify: `mine-web/src/modules/device/components/strategy/EffectiveStrategyPanel.test.ts`
- Modify: `mine-web/src/modules/device/api/strategy.ts`
- Modify: `mine-web/src/modules/device/api/strategy.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/generated/mine-api.ts`
- Create: `mine-web/e2e/retention-strategy.spec.ts`

**Steps:**
1. 先写组件测试，覆盖四个预设、等级边界、暂停、风险提示、原子 `policy` 提交和有效值来源。
2. 实现仅使用 Naive UI、UnoCSS、主题 token 和 Vue I18n 的编辑器，不增加页面私有样式。
3. 先写页面/API 失败测试，再接入预览和最近执行事实；发布前明确显示统计时点与不可逆提示。
4. 更新 OpenAPI 生成类型并补中英文全部可见文案。
5. 运行 `pnpm --dir mine-web check:ui-governance`、typecheck、lint、Vitest、build 和 Playwright，确认通过后提交 `feat(frontend): 交付数据保留策略工作台`。

### Task 7: 文档、完整验证与独立复审

**Files:**
- Modify: `docs/04-device-model-strategy.md`
- Modify: `docs/05-messaging-telemetry-data.md`
- Modify: `docs/07-frontend.md`
- Modify: `docs/09-deployment-operations.md`
- Modify: `docs/10-testing-quality-gates.md`
- Modify: `docs/11-contracts-and-database.md`
- Modify: `docs/12-roadmap-and-codex-workflow.md`
- Create: `docs/runbooks/retention-runtime.md`
- Create: `docs/reports/phase-2-retention-runtime-acceptance.md`
- Modify: `docs/README.md`

**Steps:**
1. 更新契约、数据边界、部署参数、指标、故障恢复和路线图状态。
2. 编写运行手册，明确暂停、回滚、不可逆删除、备份恢复前置条件和禁止手工无条件 `DELETE`。
3. 运行 `bash scripts/verify.sh` 与保留策略浏览器 E2E，保存准确测试数量和边界结论。
4. 使用 `requesting-code-review` 对本切片做独立复审，修复全部 Critical/Important 问题并复验。
5. 生成非现场自动化验收报告，明确不等于矿区生产放行。
6. 检查相对链接、`git diff --check` 和工作区范围后提交 `docs(retention): 完善保留策略验收与运维说明`。

