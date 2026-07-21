# 配置作用域与有效配置 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付产品、设备组、单设备三级策略继承、同优先级冲突门禁和带来源的有效在线状态策略，并接入现有设备状态判断。

**Architecture:** `mine-device-model` 提供无 Spring、无数据库依赖的确定性策略解析器；`mine-server` 保存设备组、历史成员关系、策略版本和不可变发布事实，并在写操作前计算冲突。首个 `ONLINE_STATUS` 策略只开放白名单字段 `offlineThresholdSeconds`，运行服务读取解析后的不可变结果，无发布策略时兼容现有连接配置。

**Tech Stack:** Java 21、Spring Boot 4、PostgreSQL、MyBatis、Flyway、Micrometer、Vue 3、TypeScript、Naive UI、Vitest、Playwright。

---

### Task 1: 纯 Java 有效策略解析器

**Files:**
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/strategy/StrategyScopeType.java`
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/strategy/ScopedStrategyFragment.java`
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/strategy/EffectiveStrategyResolver.java`
- Create: `mine-device-model/src/test/java/com/bosco/mine/model/strategy/EffectiveStrategyResolverTest.java`

**Step 1: Write the failing tests**

覆盖以下独立行为：

```java
@Test
void 设备值应覆盖高优先级组且高优先级组应覆盖产品默认值() { }

@Test
void 同优先级组同字段异值必须返回稳定冲突且不产生有效值() { }

@Test
void 同优先级相同值和不同字段应允许合并() { }

@Test
void 输入顺序和Map字段顺序不得改变结果与哈希() { }
```

来源断言包含 `scopeType/scopeId/releaseId/groupPriority`，冲突断言包含字段、优先级和稳定排序后的组标识。

**Step 2: Run test to verify it fails**

Run:

```bash
./mvnw -pl mine-device-model -am -Dtest=EffectiveStrategyResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是策略解析类尚不存在。

**Step 3: Write minimal implementation**

- `StrategyScopeType` 只包含 `PRODUCT/GROUP/DEVICE`。
- `ScopedStrategyFragment` 构造时复制并冻结值，组优先级限定 0..1000，非组作用域不接受组优先级。
- `EffectiveStrategyResolver.resolve(...)` 按产品、组优先级升序、设备顺序处理。
- 同优先级同字段使用规范化标量比较；不同值收集冲突，存在冲突时 `valid=false` 且不返回可消费的有效值。
- 当前只接受 `String/Boolean/Integer/Long/BigDecimal` 和 `null`，拒绝集合、数组和任意对象。
- 使用稳定字段顺序、类型前缀和 UTF-8 计算 `sha256:` 哈希，不依赖默认 Locale 或 Map 顺序。
- 所有生产 Java 类型和公开方法使用中文 Javadoc。

**Step 4: Run test to verify it passes**

重复 Step 2 命令，Expected: PASS。

**Step 5: Commit**

```bash
git add mine-device-model/src/main/java/com/bosco/mine/model/strategy mine-device-model/src/test/java/com/bosco/mine/model/strategy
git commit -m "feat(strategy): 实现三级有效策略解析器"
```

### Task 2: 在线状态策略白名单与类型校验

**Files:**
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/strategy/StrategyValidationException.java`
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/strategy/StrategyDefinition.java`
- Create: `mine-device-model/src/main/java/com/bosco/mine/model/strategy/OnlineStatusStrategyDefinition.java`
- Test: `mine-device-model/src/test/java/com/bosco/mine/model/strategy/OnlineStatusStrategyDefinitionTest.java`

**Step 1: Write the failing tests**

```java
@Test
void 应规范化合法离线阈值() { }

@Test
void 应拒绝未知字段错误类型和范围外阈值() { }

@Test
void 应拒绝空片段和超过字段数量上限的输入() { }
```

合法输入 `{ "offlineThresholdSeconds": 180 }` 规范化为不可变 Map；允许局部片段，但不能是空对象。阈值范围 1..604800。

**Step 2: Run test to verify it fails**

```bash
./mvnw -pl mine-device-model -am -Dtest=OnlineStatusStrategyDefinitionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是定义和异常类型不存在。

**Step 3: Write minimal implementation**

- `StrategyDefinition` 暴露稳定 `type()` 和 `validate(Map<String, ?>)`。
- `OnlineStatusStrategyDefinition` 类型固定为 `ONLINE_STATUS`。
- 只接收 `offlineThresholdSeconds`，将 Jackson 等反序列化产生的精确整数 `Number` 安全转换为 `Integer`，拒绝小数、溢出、布尔和文本。
- `StrategyValidationException` 携带不可变 `violations`，每项包含字段和中文安全原因，不包含完整输入。

**Step 4: Run test to verify it passes**

重复 Step 2 命令，并运行 Task 1-2：

```bash
./mvnw -pl mine-device-model -am test
```

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-device-model/src/main/java/com/bosco/mine/model/strategy mine-device-model/src/test/java/com/bosco/mine/model/strategy
git commit -m "feat(strategy): 增加在线状态策略类型校验"
```

### Task 3: 设备组与策略控制面数据库

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V20__create_strategy_scope_control_plane.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Step 1: Write the failing migration tests**

先把以下表加入 `EXPECTED_TABLES`：

```text
dev_device_group
dev_device_group_membership
dev_strategy_version
dev_strategy_release
```

新增测试断言：

- 四张表及每个字段中文注释完整。
- 产品内组编码唯一、优先级 0..1000、组状态白名单。
- 同一设备/组只允许一个当前成员关系，并有按设备和组查询的局部索引。
- 策略类型、状态、作用域、哈希和非空 JSON 对象约束。
- 发布幂等键、产品内发布序号和当前作用域查询索引。
- 已发布策略版本与发布事实禁止更新或删除。
- 设备组和策略权限种子完整。

**Step 2: Run test to verify it fails**

```bash
./mvnw -pl mine-server -am -Dtest=DatabaseMigrationIT -DfailIfNoTests=false test
```

Expected: FAIL，原因是 V20 表不存在。

**Step 3: Write minimal migration**

- 使用 UUID 主键、TIMESTAMPTZ、JSONB、乐观锁 `version` 和操作人字段。
- 成员关系使用 `effective_from/effective_to` 保存历史，禁止结束时间早于开始时间。
- 数据库外键保证组、设备、策略版本和发布存在；跨产品一致性由事务服务校验并由集成测试覆盖。
- `dev_strategy_release` 保存 scope 快照和 group priority 快照，避免后续改组优先级破坏历史解释。
- 全部表和字段使用 `COMMENT ON` 中文注释。
- 种子权限：`device:group:view/edit`、`device:strategy:view/edit/publish`。

**Step 4: Run test to verify it passes**

```bash
./mvnw -pl mine-server -am -Dtest=DatabaseMigrationIT -DfailIfNoTests=false test
```

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/resources/db/migration/V20__create_strategy_scope_control_plane.sql mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java
git commit -m "feat(strategy): 创建配置作用域控制面数据库"
```

### Task 4: 设备组管理领域服务与 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/repository/StrategyScopeMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/DeviceGroupService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/controller/DeviceGroupController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/dto/DeviceGroupResponse.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/strategy/service/DeviceGroupServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/strategy/controller/DeviceGroupControllerTest.java`

**Steps:**

1. 写失败测试覆盖分页、产品归属、编码重复、优先级范围、乐观锁、归档、跨产品成员拒绝、成员历史关闭、权限和审计。
2. 运行两个定向测试，确认因服务和控制器缺失失败。
3. 实现 Mapper、事务服务和 REST API；成员替换先计算差异，不物理删除历史。
4. 运行定向测试和 `DatabaseMigrationIT`，确认通过。
5. 使用 `feat(strategy): 实现设备组与成员管理` 提交。

### Task 5: 策略草稿、发布、冲突预检和有效值 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/repository/StrategyConfigurationMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/StrategyConfigurationService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/controller/StrategyConfigurationController.java`
- Create: matching DTOs
- Test: matching service/controller/repository integration tests
- Modify: `docs/openapi/mine-api.yaml`

**Steps:**

1. 写失败测试覆盖白名单类型、草稿乐观锁、发布版本不可变、作用域归属、发布原因、幂等键、同级冲突、相同值合并、来源、稳定哈希、回滚和审计。
2. 实现当前发布查询和批量受影响设备解析；任何冲突回滚整个事务并返回 `STRATEGY-409-001`。
3. 实现有效值 API 和稳定 DTO，更新 OpenAPI 并重新生成前端类型。
4. 运行 Server 定向与集成测试。
5. 使用 `feat(strategy): 实现策略发布与有效配置计算` 提交。

### Task 6: 在线状态服务消费有效策略

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/EffectiveOnlineStatusPolicyProvider.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceAccessStatusService.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/device/service/DeviceAccessStatusServiceTest.java`

**Steps:**

1. 写失败测试证明有效策略覆盖连接配置阈值、无发布策略兼容回退、批量查询不逐设备重复加载策略。
2. 实现批量只读 Provider 和有界缓存，按策略发布及成员变更精确失效。
3. 增加低基数 Micrometer 指标：解析结果、耗时、缓存命中和失效。
4. 运行设备状态、策略服务和架构测试。
5. 使用 `feat(strategy): 接入在线状态有效策略` 提交。

### Task 7: 策略工作台前端

**Files:**
- Create: `mine-web/src/modules/device/pages/strategy/StrategyWorkbenchPage.vue`
- Create: `mine-web/src/modules/device/components/strategy/EffectiveStrategyPanel.vue`
- Create: `mine-web/src/modules/device/api/strategy.ts`
- Modify: dynamic route component registry and route tests
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Test: matching Vitest and Playwright files

**Steps:**

1. 先写失败组件测试，覆盖产品/组/设备选择、优先级、来源、冲突、权限和服务端错误详情。
2. 使用 Naive UI、UnoCSS 和全局主题 token 实现三栏工作台，不写页面样式和硬编码文案。
3. 增加菜单权限、双语文案和浏览器契约 E2E。
4. 运行 UI governance、typecheck、lint、unit 和 build。
5. 使用 `feat(frontend): 新增有效策略工作台` 提交。

### Task 8: Gateway 节点失败回报与协议指标告警

**Files:**
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/configuration/ConfigurationSnapshotClient.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/configuration/HttpConfigurationSnapshotClient.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/configuration/ConfigurationReleaseConsumer.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/configuration/ConfigurationDistributionMetrics.java`
- Modify: Gateway tests
- Modify: Prometheus rule files and deployment tests

**Steps:**

1. 写失败测试覆盖永久快照失败上报 `FAILED`、瞬时网络失败重投、错误摘要脱敏与截断、加载/解析结果和耗时指标。
2. 实现失败回报且不改变 RabbitMQ ACK/NACK 安全语义；状态上报失败仍重投。
3. 增加低基数指标和告警规则，不使用 productId/releaseId 作为标签。
4. 运行 Gateway、Prometheus 和 Compose 配置测试。
5. 使用 `feat(observability): 补齐配置加载失败证据与告警` 提交。

### Task 9: 全量验证与阶段报告

**Files:**
- Modify: `docs/04-device-model-strategy.md`
- Modify: `docs/11-contracts-and-database.md`
- Modify: `docs/12-roadmap-and-codex-workflow.md`
- Create: `docs/reports/phase-2-strategy-scope-acceptance.md`

**Steps:**

1. 运行 `./mvnw -B -ntp verify`。
2. 运行前端 UI governance、typecheck、lint、unit、build 和非现场 Playwright。
3. 运行 Compose、Prometheus、SBOM 和许可证门禁。
4. 更新路线图，只把有自动化证据的条目标记完成；真实设备、容量和长稳继续标记 `DEFERRED`。
5. 使用 requesting-code-review 审查 Critical/Important 问题并修复。
6. 使用 `docs(strategy): 补充作用域策略验收证据` 提交。
