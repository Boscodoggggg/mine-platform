# Aggregation Strategy Runtime Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付可发布、可分发、可有界执行、可稳定查询并能约束数据保留顺序的 `AGGREGATION` 聚合策略运行闭环。

**Architecture:** 控制面复用三级策略作用域和不可变运行快照，Telemetry 独立校验后原子切换逐设备最后正确镜像。平台自管调度器把已越过修正窗口的数值遥测写成版本化 rollup；查询以原始摄取水位和聚合水位固定分页快照，保留任务只删除已被成功聚合覆盖的数据。

**Tech Stack:** Java 25、Spring Boot 4、MyBatis、JDBC、PostgreSQL 17、TimescaleDB 2.x Apache 模式、Flyway、RabbitMQ、Micrometer、Vue 3、TypeScript、Naive UI、Vitest、Playwright。

---

## 实施约束

- 每项生产代码前必须先运行对应失败测试并确认失败原因是能力缺失。
- Java 新增和修改的公共类型、方法使用中文 Javadoc。
- 新增数据库表及全部字段使用 `COMMENT ON` 中文注释，集成测试逐项校验。
- 前端用户可见文案全部进入中英文 locale，业务页面不新增 `<style>` 或硬编码颜色。
- 真实设备、现场网络、容量、HA、备份恢复和长稳不在本计划内，不得把自动化结论写成生产放行。

### Task 1：共享聚合策略白名单

**Files:**
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/strategy/AggregationStrategyDefinition.java`
- Create: `mine-device-model/src/test/java/com/bosco/mine/model/strategy/AggregationStrategyDefinitionTest.java`

**Step 1: 写失败测试**

覆盖合法规范化、未知顶层/内部字段、非法类型、规则空或超过 100、属性格式、桶白名单、重复属性桶和修正窗口小于最大桶宽两倍。

规范化期望示例：

```java
assertThat(definition.validate(Map.of("policy", Map.of(
        "enabled", true,
        "correctionWindowSeconds", 7200,
        "rules", List.of(
                Map.of("propertyId", "speed", "bucketSeconds", 300),
                Map.of("propertyId", "payload", "bucketSeconds", 3600)
        )
)))).isEqualTo(Map.of("policy", Map.of(
        "enabled", true,
        "correctionWindowSeconds", 7200,
        "rules", List.of(
                Map.of("bucketSeconds", 3600, "propertyId", "payload"),
                Map.of("bucketSeconds", 300, "propertyId", "speed")
        )
)));
```

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-device-model -Dtest=AggregationStrategyDefinitionTest test`

Expected: FAIL，类尚不存在。

**Step 3: 最小实现**

实现 `StrategyDefinition`，顶层仅接受 `policy`，内部仅接受 `enabled`、`correctionWindowSeconds`、`rules`；规则只接受 `propertyId` 与 `bucketSeconds`。所有错误通过稳定字段路径返回 `StrategyValidationException.Violation`。

**Step 4: 确认 GREEN**

Run: `./mvnw -pl mine-device-model -Dtest=AggregationStrategyDefinitionTest test`

Expected: PASS。

**Step 5: 提交**

```bash
git add mine-device-model
git commit -m "feat(strategy): 注册聚合策略白名单"
```

### Task 2：控制面注册与生命周期快照

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V30__enable_aggregation_strategy.sql`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/StrategyConfigurationService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/StrategyRuntimeSnapshotService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/DeviceGroupService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceCommandService.java`
- Modify: matching server unit and integration tests
- Modify: `docs/openapi/mine-api.yaml`

**Step 1: 写失败测试**

增加以下断言：

- 可以创建和发布规范化 `AGGREGATION` 版本，非法定义返回 `STRATEGY-400-001`。
- 发布/回滚生成独立运行快照。
- 设备创建/归档、组成员、优先级和归档变化捕获聚合增量。
- V30 把版本、发布、运行快照约束开放到 `AGGREGATION`，且迁移可重复从空库执行。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-server -am -Dtest='StrategyConfigurationServiceTest,StrategyRuntimeSnapshotServiceTest,DeviceGroupServiceTest,DeviceCommandServiceTest,DatabaseMigrationIT' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，策略类型未注册且数据库约束拒绝。

**Step 3: 最小实现**

在控制面定义注册表、运行快照注册表和所有设备作用域变化点加入 `AggregationStrategyDefinition.TYPE`。V30 只变更已有 check constraint，不新建业务表；OpenAPI 的 `StrategyType` enum 同步开放。

**Step 4: 确认 GREEN**

重复 Step 2 命令，Expected: PASS。

**Step 5: 提交**

```bash
git add mine-server docs/openapi/mine-api.yaml
git commit -m "feat(strategy): 启用聚合策略控制面"
```

### Task 3：Telemetry 最后正确聚合策略镜像

**Files:**
- Create: `mine-telemetry/src/main/resources/db/migration/V17__create_aggregation_policy_mirror.sql`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationPolicy.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationPolicyRepository.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/strategy/StrategySnapshotIntegrity.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/strategy/StrategyPolicyRepository.java`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/strategy/StrategySnapshotLoaderTest.java`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/strategy/StrategyPolicyRepositoryIT.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/aggregation/AggregationPolicyRepositoryTest.java`

**Step 1: 写失败测试**

覆盖独立定义校验、快照加载、类型化 JSON 约束、原子激活、显式删除、修订防倒退、last-known-good 和只查询指定设备策略。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-telemetry -am -Dtest='AggregationPolicyRepositoryTest,StrategySnapshotLoaderTest,StrategyPolicyRepositoryIT' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，Telemetry 不支持 `AGGREGATION` 且镜像表不存在。

**Step 3: 最小实现**

V17 扩展运行修订类型并创建 `telemetry_aggregation_policy`。表保存设备、产品、运行修订、快照、规范化 JSON、哈希、生成启用字段、修正窗口和更新时间；规则保留在 JSON 中，由共享定义和数据库 JSON 结构约束双重校验。

**Step 4: 确认 GREEN**

重复 Step 2 命令，Expected: PASS。

**Step 5: 提交**

```bash
git add mine-telemetry
git commit -m "feat(telemetry): 原子加载聚合策略镜像"
```

### Task 4：版本化聚合表与执行事实

**Files:**
- Create: `mine-telemetry/src/main/resources/db/migration/V18__create_numeric_rollup_runtime.sql`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationExecutionRepository.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/aggregation/AggregationExecutionRepositoryIT.java`

**Step 1: 写失败集成测试**

断言 rollup 版本主键、源摄取水位、策略修订证据、同义重试幂等、新版本并存、执行事实终态保护、索引以及全部中文注释。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-telemetry -Dtest=AggregationExecutionRepositoryIT test`

Expected: FAIL，V18 表和仓储不存在。

**Step 3: 最小实现**

创建 `telemetry_numeric_rollup`、`telemetry_aggregation_execution` 和必要的覆盖水位索引；使用 JDBC 事务写入一批版本和累计进度。

**Step 4: 确认 GREEN并提交**

Run: `./mvnw -pl mine-telemetry -Dtest=AggregationExecutionRepositoryIT test`

Commit: `feat(telemetry): 建立版本化数值聚合事实`

### Task 5：有界聚合执行、调度和指标

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationExecutor.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationScheduler.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationProperties.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationConfiguration.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationMetrics.java`
- Create/Modify: matching unit tests, `application.yml`, Compose env, Prometheus rules

**Step 1: 写失败测试**

覆盖锁忙跳过、无策略成功、闭合桶计算、窗口内迟到被纳入、超窗口迟到计数、批次上限 `PARTIAL`、失败脱敏、失联恢复、固定低基数指标和调度禁用。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-telemetry -Dtest='AggregationExecutorTest,AggregationSchedulerTest,AggregationMetricsTest' test`

Expected: FAIL，新执行器尚不存在。

**Step 3: 最小实现**

只使用数据库当前时间、advisory lock、固定桶白名单和有界批次。任务默认关闭，部署配置显式启用；异常不得删除或覆盖原始遥测。

**Step 4: GREEN、集成验证并提交**

Run: `./mvnw -pl mine-telemetry -Dtest='Aggregation*Test,Aggregation*IT' test`

Commit: `feat(telemetry): 执行有界数值聚合任务`

### Task 6：聚合覆盖约束数据保留

**Files:**
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/retention/RetentionExecutionRepository.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/retention/RetentionMetrics.java`
- Modify: retention unit/integration tests

**Step 1: 写失败测试**

验证有聚合规则的属性在覆盖缺失、版本失败或仍处修正窗口时不会删除；覆盖成功后才允许删除；无聚合规则属性保持原行为；阻塞量使用固定标签指标。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-telemetry -Dtest='RetentionExecutorTest,RetentionExecutionRepositoryIT,RetentionMetricsTest' test`

Expected: FAIL，现有清理 SQL 未检查聚合覆盖。

**Step 3: 实现、GREEN并提交**

在删除候选 SQL 中以最终聚合策略和成功 rollup 覆盖进行 fail-closed 判断，不依赖调度先后。

Commit: `fix(retention): 禁止清理未聚合覆盖的原始数据`

### Task 7：冷热历史查询和显式重算 API

**Files:**
- Modify: server telemetry query repository/service/controller and DTO
- Create: aggregation recompute controller/service/repository DTO
- Modify: `docs/openapi/mine-api.yaml`
- Modify: generated frontend API type
- Create/Modify: server unit, PostgreSQL integration and OpenAPI tests

**Step 1: 写失败测试**

覆盖 rollup 冷数据、修正窗口原始热数据、边界合并、最差质量、样本数、`rollupWatermark` 分页稳定、后台重算不污染旧分页、无匹配规则时的显式数据来源，以及重算权限/范围/幂等/审计。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-server -am -Dtest='DeviceTelemetryQueryServiceTest,RedisTimescaleDeviceTelemetryRepositoryIT,*Aggregation*Test' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，响应没有聚合水位且只读原始表。

**Step 3: 实现、GREEN并提交**

首页固定两个水位，后续页强制回传。重算必须使用有界时间范围和 `governance:aggregation:execute`，只创建请求事实，由 Telemetry 执行。

Commit: `feat(aggregation): 提供稳定冷热查询与受控重算`

### Task 8：策略工作台和运行治理页面

**Files:**
- Modify: `mine-web/src/modules/device/pages/strategy/StrategyWorkbenchPage.vue`
- Modify: matching API、unit test、locales、typings
- Create: aggregation execution/recompute governance page if separate navigation is required
- Modify: route whitelist, migration/menu only when a separate page is created
- Modify: Playwright E2E

**Step 1: 写失败前端测试**

覆盖聚合类型选择、固定桶选项、规则去重、修正窗口校验、脏表单发布门禁、最终有效值、执行状态、权限隐藏、错误/空状态和重算二次确认。

**Step 2: 确认 RED**

Run: `pnpm --dir mine-web test:unit -- StrategyWorkbenchPage.test.ts`

Expected: FAIL，页面尚无聚合编辑器。

**Step 3: 实现、GREEN并提交**

使用 Naive UI、UnoCSS、主题 token 和 Vue I18n；不得加入自由表达式输入。

Run:

```bash
pnpm --dir mine-web check:ui-governance
pnpm --dir mine-web typecheck
pnpm --dir mine-web lint:check
pnpm --dir mine-web test:unit
pnpm --dir mine-web build
```

Commit: `feat(frontend): 交付聚合策略运行工作台`

### Task 9：运行手册、验收证据和全仓门禁

**Files:**
- Create: `docs/runbooks/aggregation-runtime.md`
- Create: `docs/reports/phase-2-aggregation-runtime-acceptance.md`
- Modify: architecture、contract、deployment、testing、roadmap documents
- Modify: example env, Compose validation, monitoring rules

**Step 1: 补自动化验收证据**

记录准确的单元、集成、数据库迁移、前端、E2E 和规则数量；说明真实现场验证未执行。

**Step 2: 执行完整门禁**

Run:

```bash
bash scripts/verify.sh
pnpm --dir mine-web test:e2e --workers=1
git diff --check
```

Expected: 全部 PASS，工作区只包含本计划变更。

**Step 3: 评审与提交**

检查事务边界、查询分页一致性、数据库注释、指标标签、权限、审计、前端治理和延期边界。

Commit: `docs(aggregation): 补全运行手册与自动化验收`
