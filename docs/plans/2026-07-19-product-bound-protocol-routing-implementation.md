# Product-bound Protocol Routing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让 MQTT JSON、MQTT Binary 和统一 TCP 接入按产品发布绑定选择解析器，同时保留旧 TCP 协议显式监听器兼容模式。

**Architecture:** 产品配置快照新增受控协议绑定，Gateway 在可信身份建立后只读内存索引选择解析器。MQTT 直接使用 Broker 身份和消息边界；统一 TCP 先处理固定登录帧，再为连接安装产品绑定的插件拆帧器；Legacy TCP 保持现有静态监听行为。

**Tech Stack:** Java 21、Spring Boot 4、Netty、RabbitMQ、PostgreSQL/Flyway、MyBatis、Vue 3、Naive UI、Vue I18n、Vitest。

---

### Task 1: 定义 MQTT Binary 稳定 SDK

**Files:**
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/mqtt/MqttBinaryDecodeContext.java`
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/mqtt/MqttBinaryDecodedMessage.java`
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/mqtt/MqttBinaryProtocolPlugin.java`
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/mqtt/MqttBinaryProtocolException.java`
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/mqtt/package-info.java`
- Test: `mine-protocol-sdk/src/test/java/com/bosco/mine/protocol/sdk/mqtt/MqttBinaryProtocolContractTest.java`

**Step 1: Write the failing test**

测试期望插件接收完整 MQTT Payload 和可信上下文，返回不含产品/设备覆盖字段的不可变遥测：

```java
MqttBinaryDecodedMessage message = new MqttBinaryDecodedMessage(
        "boot-1", 7L, Instant.parse("2026-07-19T00:00:00Z"), false,
        Map.of("engine.rpm", 1350));
assertThat(message.properties()).containsEntry("engine.rpm", 1350);
```

覆盖属性递归不可变、数量/深度上限、身份上下文格式和固定错误码。

**Step 2: Run test to verify it fails**

Run: `./mvnw -pl mine-protocol-sdk -Dtest=MqttBinaryProtocolContractTest test`

Expected: FAIL，类型尚不存在。

**Step 3: Write minimal implementation**

接口只暴露 `protocolKey()`、`protocolVersion()`、`apiMajorVersion()` 和：

```java
MqttBinaryDecodedMessage decode(MqttBinaryDecodeContext context, byte[] payload);
```

所有新增后端类型使用中文 Javadoc。

**Step 4: Run test to verify it passes**

Run: `./mvnw -pl mine-protocol-sdk -Dtest=MqttBinaryProtocolContractTest test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-protocol-sdk
git commit -m "feat(protocol): 新增 MQTT 二进制插件契约"
```

### Task 2: 加载和校验 MQTT Binary 插件

**Files:**
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/mqtt/MqttBinaryProtocolPluginRegistry.java`
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/mqtt/package-info.java`
- Test: `mine-protocol-engine/src/test/java/com/bosco/mine/protocol/engine/mqtt/MqttBinaryProtocolPluginRegistryTest.java`
- Create: `examples/protocol-plugins/haul-truck/src/main/java/com/bosco/mine/example/protocol/haultruck/HaulTruckMqttBinaryProtocolPlugin.java`
- Create: `examples/protocol-plugins/haul-truck/src/main/resources/META-INF/services/com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolPlugin`
- Test: `examples/protocol-plugins/haul-truck/src/test/java/com/bosco/mine/example/protocol/haultruck/HaulTruckMqttBinaryProtocolPluginTest.java`

**Step 1: Write failing registry tests**

覆盖 ServiceLoader、重复键、非法键、空版本和 API 主版本不兼容。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-protocol-engine -am -Dtest=MqttBinaryProtocolPluginRegistryTest test`

Expected: FAIL，注册表尚不存在。

**Step 3: Implement registry and example plugin**

注册表采用完整校验后一次性发布不可变索引。示例 MQTT 插件复用矿卡业务字段解析，但 MQTT Payload 是一条完整消息，不声明 TCP 帧规则，也不生成应用 ACK。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-protocol-engine,examples/protocol-plugins/haul-truck -am test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-protocol-engine examples/protocol-plugins/haul-truck
git commit -m "feat(protocol): 支持发现 MQTT 二进制插件"
```

### Task 3: 为产品配置快照增加协议绑定

**Files:**
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/binding/ProductProtocolBinding.java`
- Test: `mine-protocol-engine/src/test/java/com/bosco/mine/protocol/engine/binding/ProductProtocolBindingTest.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/configuration/ProtocolSnapshotRegistry.java`
- Modify: `mine-gateway/src/test/java/com/bosco/mine/gateway/configuration/ProtocolSnapshotRegistryTest.java`

**Step 1: Write failing binding and snapshot tests**

合法组合限定为：

```text
MQTT + JSON   + CONFIGURATION + TRUSTED_IDENTITY
MQTT + BINARY + JAVA_PLUGIN   + TRUSTED_IDENTITY
TCP  + BINARY + JAVA_PLUGIN   + TRUSTED_IDENTITY|LEGACY_LISTENER
```

JSON 配置必须携带 definition；Java 插件必须携带 pluginKey/pluginVersion。测试 schemaVersion 1 向后兼容和 schemaVersion 2 原子切换。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-gateway -am -Dtest=ProductProtocolBindingTest,ProtocolSnapshotRegistryTest test`

Expected: FAIL，绑定类型和 schema 2 尚不存在。

**Step 3: Implement minimal snapshot variant**

`ActiveProtocol` 保存绑定以及可选 MQTT JSON 执行计划；插件实例由 Gateway 注册表按键解析，不序列化进快照。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-gateway -am -Dtest=ProductProtocolBindingTest,ProtocolSnapshotRegistryTest test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-protocol-engine mine-gateway
git commit -m "feat(protocol): 增加产品协议绑定快照"
```

### Task 4: MQTT 按产品路由 JSON 与 Binary

**Files:**
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/normalize/DeviceMessageNormalizer.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/bootstrap/GatewayRabbitConfiguration.java`
- Modify: `mine-gateway/src/test/java/com/bosco/mine/gateway/normalize/DeviceMessageNormalizerTest.java`
- Modify: `mine-gateway/src/test/java/com/bosco/mine/gateway/mqtt/DeviceMessageConsumerTest.java`

**Step 1: Write failing routing tests**

测试同一 routing key 入口下，产品 A 执行 JSON plan，产品 B 调用 Binary plugin；协议类型不匹配、插件缺失、插件版本不符和插件受控异常均拒绝原消息。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-gateway -am -Dtest=DeviceMessageNormalizerTest,DeviceMessageConsumerTest test`

Expected: FAIL，Binary 路由分支尚不存在。

**Step 3: Implement product-bound routing**

先解析受控 Topic 和可信身份，再读取 `ProtocolSnapshotRegistry`。Binary 插件收到 `MqttBinaryDecodeContext`，平台身份字段始终由 Gateway 注入。

**Step 4: Verify GREEN and regression**

Run: `./mvnw -pl mine-gateway -am test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-gateway
git commit -m "feat(gateway): 按产品路由 MQTT 报文解析器"
```

### Task 5: 持久化并发布产品插件绑定

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V42__add_product_protocol_plugin_binding.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/controller/DeviceModelManagementController.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceModelManagementService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/repository/DeviceModelManagementMapper.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/configuration/repository/ProtocolConfigurationMapper.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/configuration/service/ProtocolConfigurationService.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/configuration/service/ProtocolConfigurationServiceTest.java`
- Modify: `docs/openapi/mine-api.yaml`

**Step 1: Write failing migration and service tests**

新增字段 `parser_type`、`plugin_key`、`plugin_version`、`routing_mode`，为表中每个新增字段提供中文 `COMMENT ON COLUMN`。数据库测试校验注释和组合约束；服务测试校验 schema 2 快照和非法组合拒绝。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=ProtocolConfigurationServiceTest,DatabaseMigrationIT test`

Expected: FAIL，新字段和发布逻辑不存在。

**Step 3: Implement migration, API and publishing**

MQTT JSON 沿用字段映射编译门禁；MQTT/TCP Binary 要求插件键与版本，黄金样本首批使用十六进制字符串承载。生成 schema 2 快照并维持旧 schema 1 读取兼容。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am test`

Expected: PASS。

**Step 5: Regenerate frontend API types**

Run: `pnpm --dir mine-web generate:api`

Expected: `mine-web/src/generated/mine-api.ts` 更新且生成成功。

**Step 6: Commit**

```bash
git add mine-server docs/openapi/mine-api.yaml mine-web/src/generated/mine-api.ts
git commit -m "feat(protocol): 发布产品插件绑定配置"
```

### Task 6: 定义并验证统一 TCP 登录帧

**Files:**
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/login/TcpLoginFrame.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/login/TcpLoginFrameCodec.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/login/TcpLoginAuthenticator.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/login/package-info.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/session/DeviceSessionRegistry.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/session/DeviceIdentitySnapshotLoader.java`
- Modify: `mine-gateway/pom.xml`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/tcp/login/TcpLoginFrameCodecTest.java`

**Step 1: Write failing login codec tests**

覆盖固定魔数、版本、长度、产品/设备/凭据格式、截断、超长和敏感错误脱敏。认证通过接口注入；设备只在 TLS 连接中提交一次性签发的高熵 secret，Gateway 使用身份快照中的 BCrypt 散列校验且不记录原文。生产未配置认证材料时统一入口保持关闭。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-gateway -am -Dtest=TcpLoginFrameCodecTest test`

Expected: FAIL，登录帧类型不存在。

**Step 3: Implement bounded codec and authentication boundary**

登录帧使用独立固定长度字段，最大 512 字节。Codec 只做结构解析；Authenticator 使用 `productKey + deviceCode + username` 定位可信设备并用 BCrypt 校验 secret，不把凭据交给协议插件。身份快照只增加不可逆散列，保留旧六参数构造器兼容现有测试和 Legacy 数据。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-gateway -am -Dtest=TcpLoginFrameCodecTest test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/login mine-gateway/src/test/java/com/bosco/mine/gateway/tcp/login
git commit -m "feat(gateway): 增加统一 TCP 登录协议"
```

### Task 7: 登录后为 TCP 连接选择产品插件

**Files:**
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/ProductBoundTcpChannelHandler.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/TcpChannelInitializer.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/TcpGatewayServer.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/bootstrap/GatewayTcpProperties.java`
- Modify: `mine-gateway/src/main/resources/application.yml`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/tcp/ProductBoundTcpChannelHandlerTest.java`
- Modify: `mine-gateway/src/test/java/com/bosco/mine/gateway/tcp/TcpGatewayServerTest.java`

**Step 1: Write failing dynamic pipeline tests**

测试登录成功后按产品绑定选择唯一插件、安装该插件帧解码器、创建连接独占会话；登录失败、产品绑定不是 TCP、插件缺失/版本不匹配和连接内身份切换均关闭连接。现有 Legacy 测试必须继续通过。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-gateway -am -Dtest=ProductBoundTcpChannelHandlerTest,TcpGatewayServerTest test`

Expected: FAIL，产品绑定 handler 尚不存在。

**Step 3: Implement `UNIFIED` and `LEGACY` listener modes**

`mine.gateway.tcp.mode=UNIFIED|LEGACY`。UNIFIED 不接受静态 `protocol-key`，登录后查询产品快照；LEGACY 保持当前静态插件行为。

**Step 4: Verify GREEN and gateway regression**

Run: `./mvnw -pl mine-gateway -am test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-gateway
git commit -m "feat(gateway): 支持统一 TCP 入口按产品选择插件"
```

### Task 8: 前台配置产品解析方式

**Files:**
- Modify: `mine-web/src/modules/device/pages/model-management/DeviceModelManagementPage.vue`
- Modify: `mine-web/src/modules/device/pages/model-management/DeviceModelManagementPage.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/modules/device/api/device-model.ts`

**Step 1: Write failing component tests**

测试 MQTT JSON 展示字段映射，MQTT Binary/TCP Binary 展示插件键和版本，TCP 展示统一/兼容路由模式；非法组合不能提交，所有文案通过 I18n 获取。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web vitest run src/modules/device/pages/model-management/DeviceModelManagementPage.test.ts`

Expected: FAIL，新字段尚未渲染。

**Step 3: Implement Naive UI conditional form**

只使用 Naive UI 和 UnoCSS，禁止页面 `<style>`、内联 CSS、硬编码颜色与可见文案。

**Step 4: Verify GREEN and governance**

Run: `pnpm --dir mine-web check:ui-governance`

Run: `pnpm --dir mine-web typecheck && pnpm --dir mine-web lint && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`

Expected: 全部 PASS。

**Step 5: Commit**

```bash
git add mine-web
git commit -m "feat(frontend): 支持配置产品协议解析器"
```

### Task 9: 文档、部署兼容和总体验收

**Files:**
- Modify: `docs/03-device-access-protocol.md`
- Modify: `docs/09-deployment-operations.md`
- Modify: `docs/runbooks/tcp-binary-gateway.md`
- Modify: `docs/10-testing-quality-gates.md`
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/env/standalone.env.example`
- Test: `deploy/tests/compose-config.sh`

**Step 1: Add deployment contract tests first**

更新 Compose 校验，要求默认示例明确选择 `LEGACY`，统一入口只有配置认证材料时才能启用。

**Step 2: Verify RED**

Run: `bash deploy/tests/compose-config.sh`

Expected: FAIL，新参数尚未配置。

**Step 3: Update deployment and documentation**

说明 MQTT 单端口产品路由、TCP Unified 主路径、TCP Legacy 例外路径、插件离线安装边界、升级回退和节点加载失败处理。

**Step 4: Run focused and full verification**

Run: `./mvnw verify`

Run: `pnpm --dir mine-web check:ui-governance`

Run: `pnpm --dir mine-web typecheck && pnpm --dir mine-web lint && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`

Run: `bash scripts/verify.sh`

Expected: 全部可在当前环境执行的门禁 PASS；需要 Docker 或现场 secret 的检查若不可执行，记录明确原因。

**Step 5: Commit**

```bash
git add docs deploy
git commit -m "docs(protocol): 补充产品协议路由运行手册"
```
