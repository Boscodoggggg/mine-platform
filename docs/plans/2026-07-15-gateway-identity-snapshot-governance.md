# Gateway 身份快照版本治理 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 gateway 身份快照增加版本单调保护、摘要幂等和内部运维状态查询。

**Architecture:** loader 对单次文件字节计算 SHA-256 并解析候选快照；registry 在同一个原子快照中执行版本比较并保存身份索引与版本元数据；Actuator InfoContributor 只读暴露当前生效状态。失败始终保留 last-known-good，回滚使用更高版本重新发布旧内容。

**Tech Stack:** Java 21、Spring Boot Actuator、Jackson、JUnit 6、AssertJ、Maven。

---

### Task 1：注册表版本单调保护

**Files:**

- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/session/DeviceSessionRegistry.java`
- Create: `mine-gateway/src/test/java/com/bosco/mine/gateway/session/DeviceSessionRegistryVersionTest.java`
- Modify: 使用 `DeviceSessionRegistry.replace` 的现有 gateway 测试

**Steps:**

1. 先写失败测试，覆盖首次激活、更高版本替换、同版本同摘要幂等、同版本异摘要拒绝、低版本拒绝和拒绝后 last-known-good 不变。
2. 运行 `./mvnw -B -ntp -pl mine-gateway -am -Dtest=DeviceSessionRegistryVersionTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认因为版本化 API 不存在而失败。
3. 在注册表的同一个 `AtomicReference` 中加入版本、SHA-256 和设备数量，提供版本化 `activate` 方法、结果枚举和只读状态记录；所有新增 Java 类型和方法写中文 Javadoc。
4. 把现有测试中的直接身份注入改为显式测试版本和合法 SHA-256，删除无版本替换入口。
5. 重跑定向测试，确认通过。

### Task 2：loader 摘要和 Actuator 状态

**Files:**

- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/session/DeviceIdentitySnapshotLoader.java`
- Modify: `mine-gateway/src/test/java/com/bosco/mine/gateway/session/DeviceIdentitySnapshotLoaderTest.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/bootstrap/GatewayRabbitConfiguration.java`
- Modify: `mine-gateway/src/test/java/com/bosco/mine/gateway/bootstrap/GatewayRabbitConfigurationTest.java`

**Steps:**

1. 先扩展 loader 测试，使用连续文件发布验证摘要、版本升级、同版本重复、同版本篡改和低版本拒绝；先运行并确认失败。
2. loader 一次读取原始字节，以 JDK SHA-256 生成小写十六进制摘要，解析正 long 版本后调用 registry `activate`。
3. 先写配置测试，断言启用 RabbitMQ 时存在身份快照 InfoContributor 且输出 version、sha256、deviceCount；运行并确认失败。
4. 在 RabbitMQ 配置中注册内部 Actuator InfoContributor，数据只来自 registry 当前原子状态。
5. 运行 gateway 单元测试，确认通过。

### Task 3：文档、模块验证和提交

**Files:**

- Modify: `docs/05-messaging-telemetry-data.md`
- Modify: `docs/runbooks/device-onboarding.md`
- Modify: `docs/reports/phase-1-acceptance.md`

**Steps:**

1. 更新发布规则、回滚方式和 `/actuator/info` 核验方法，删除旧限制。
2. 运行 `./mvnw -B -ntp -pl mine-gateway -am verify`。
3. 运行 `git diff --check`，复核全部后端新增代码均有中文 Javadoc。
4. 执行代码审查，修复 Critical、Important 和 Minor 问题并重跑定向验证。
5. 使用 `feat(gateway): 增加身份快照版本保护` 提交实现和文档。
