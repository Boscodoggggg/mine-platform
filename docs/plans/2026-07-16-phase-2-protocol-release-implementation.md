# 阶段 2 协议配置发布闭环 Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use test-driven-development and executing-plans to implement this plan task-by-task.

**Goal:** 交付三种 MQTT JSON 报文的配置化解析、黄金样本发布门禁、配置包热加载和可审计回滚闭环。

**Architecture:** `mine-protocol-engine` 提供 Server/Gateway 共用的纯 Java 编译与执行内核；`mine-server` 保存草稿、样本、测试运行、不可变配置包和发布事实；`mine-gateway` 拉取并原子切换完整快照，失败保留 last-known-good。发布事件只传标识和哈希，运行节点通过受权内部接口获取快照。

**Tech Stack:** Java 21、Spring Boot 4、Jackson、PostgreSQL、MyBatis、Flyway、RabbitMQ、Vue 3、TypeScript、Naive UI、Vitest、Playwright。

---

### Task 1: MQTT JSON 协议编译与执行内核

**Files:**
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/json/MqttJsonProtocolDefinition.java`
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/json/MqttJsonProtocolCompiler.java`
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/json/MqttJsonExecutionPlan.java`
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/ProtocolValidationException.java`
- Test: `mine-protocol-engine/src/test/java/com/bosco/mine/protocol/engine/json/MqttJsonProtocolCompilerTest.java`

**Steps:**
1. 写失败测试，覆盖点路径读取、嵌套字段、必填字段、目标重复、目标不存在、类型不兼容和深度/数量上限。
2. 运行 `./mvnw -pl mine-protocol-engine -am -Dtest=MqttJsonProtocolCompilerTest test`，确认因类缺失失败。
3. 实现不可变定义、校验器和执行计划；Java 代码全部使用中文 Javadoc。
4. 再次运行定向测试，确认通过。

### Task 2: 白名单字段转换和统一解析结果

**Files:**
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/json/FieldTransform.java`
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/json/ProtocolExecutionResult.java`
- Test: `mine-protocol-engine/src/test/java/com/bosco/mine/protocol/engine/json/MqttJsonExecutionPlanTest.java`

**Steps:**
1. 写失败测试覆盖 `NONE/SCALE/OFFSET/SCALE_OFFSET/ENUM`、时间和 sequence 元数据、身份不匹配及转换失败。
2. 运行定向测试，确认 RED 原因是执行能力缺失。
3. 实现最小白名单转换和结构化错误，不执行任意表达式。
4. 运行 Task 1-2 全部测试，确认 GREEN。

### Task 3: 黄金样本回放与规范化哈希

**Files:**
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/sample/ProtocolSample.java`
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/sample/ProtocolSampleRunner.java`
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/snapshot/ProtocolSnapshotHasher.java`
- Test: `mine-protocol-engine/src/test/java/com/bosco/mine/protocol/engine/sample/ProtocolSampleRunnerTest.java`
- Create: `examples/protocols/mqtt-json/{flat,nested,enum-scale}/manifest.json`

**Steps:**
1. 写失败测试证明三种样本成功、破坏性字段修改失败、对象字段顺序不改变哈希。
2. 运行定向测试确认 RED。
3. 实现 Server/Gateway 可共同调用的样本回放和 SHA-256。
4. 运行 `./mvnw -pl mine-protocol-engine -am test`，确认通过。

### Task 4: 协议样本和配置发布数据库

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V16__create_protocol_release_control_plane.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Steps:**
1. 先在迁移集成测试中断言协议样本、测试运行、配置包、发布、目标和节点状态表、约束、索引及全部中文注释。
2. 运行 `./mvnw -pl mine-server -am -Dtest=DatabaseMigrationIT verify`，确认 RED。
3. 创建 V16，已发布行不可原地修改，回滚通过新发布表达；为协议页面增加查看/测试/发布/回滚权限。
4. 再次运行迁移测试，确认空库和升级路径通过。

### Task 5: 样本测试、配置包发布和回滚 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/protocol/**`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/configuration/**`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceModelManagementService.java`
- Modify: `docs/openapi/mine-api.yaml`
- Test: matching controller/service/repository tests

**Steps:**
1. 写失败测试覆盖权限、乐观锁、样本全量回放、门禁失败、内容哈希、幂等发布、原因必填、历史包回滚和审计。
2. 实现事务边界、Mapper 和 Outbox 事件；禁止发布现有 `EXPRESSION` 转换。
3. 提供内部快照接口和独立服务令牌校验，响应不含任何 secret。
4. 运行 Server 定向单元与集成测试，更新 OpenAPI 并生成前端类型。

### Task 6: Gateway 原子热加载与 last-known-good

**Files:**
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/configuration/**`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/normalize/DeviceMessageNormalizer.java`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/configuration/ProtocolSnapshotRegistryTest.java`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/normalize/DeviceMessageNormalizerTest.java`

**Steps:**
1. 写失败测试覆盖首次加载、高版本切换、幂等事件、低版本拒绝、同版本异哈希拒绝、编译失败保留 last-known-good 和回滚新版本。
2. 实现快照 HTTP 拉取、哈希校验、本地编译、原子注册表和节点状态上报。
3. 将固定 JSON normalizer 改为优先按产品执行已加载计划，未发布产品继续使用 Phase 1 兼容适配器。
4. 运行 Gateway 单元和 RabbitMQ 集成测试。

### Task 7: 协议工作台前端

**Files:**
- Modify: `mine-web/src/modules/device/pages/model-management/DeviceModelManagementPage.vue`
- Create: `mine-web/src/modules/device/components/protocol/ProtocolSampleWorkbench.vue`
- Create: `mine-web/src/modules/device/components/protocol/ConfigReleasePanel.vue`
- Modify: `mine-web/src/modules/device/api/device-model.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Test: matching `*.test.ts`

**Steps:**
1. 使用 frontend-design 前先写失败组件测试，覆盖样本编辑、逐条结果、发布门禁、影响摘要、节点状态和回滚确认。
2. 使用 Naive UI 和 UnoCSS 实现，不添加页面 `<style>`、内联 CSS、硬编码颜色或可见文案。
3. 更新双语资源和生成类型。
4. 运行 `pnpm --dir mine-web check:ui-governance`、typecheck、lint、unit test 和 build。

### Task 8: 纵向 E2E、文档和验收

**Files:**
- Create: `mine-web/e2e/protocol-release.spec.ts`
- Create: `docs/reports/phase-2-protocol-release-acceptance.md`
- Modify: `docs/03-device-access-protocol.md`
- Modify: `docs/04-device-model-strategy.md`
- Modify: `docs/11-contracts-and-database.md`

**Steps:**
1. E2E 创建三种协议样本，发布后发送三类 MQTT JSON 并断言统一遥测。
2. 修改映射制造黄金样本失败，断言发布被阻止；发布坏快照事件，断言 Gateway 继续使用 last-known-good。
3. 回滚到历史包并断言无重启恢复旧解析行为和节点 `LOADED`。
4. 运行 `./mvnw -B -ntp verify`、前端全门禁和相关 Compose 冒烟。
5. 使用 requesting-code-review 完成审查，修复全部 Critical/Important 问题。
