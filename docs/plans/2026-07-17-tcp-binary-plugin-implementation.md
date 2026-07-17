# TCP Binary Protocol Plugin Vertical Slice Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use test-driven-development and executing-plans to implement this plan task-by-task.

**Goal:** 交付稳定 TCP 二进制协议 SPI、矿卡示例插件和具备可靠 ACK 语义的受控 Gateway TCP 接入链路。

**Architecture:** `mine-protocol-sdk` 定义不依赖框架的插件 ABI，`mine-protocol-engine` 负责 ServiceLoader 注册与兼容校验，独立示例模块实现有界长度帧和 CRC16。`mine-gateway` 使用 Netty 提供 TLS/连接治理/拆帧/可信身份绑定，把解析结果转换成统一遥测并在 RabbitMQ confirm 后发送 ACK。

**Tech Stack:** Java 21、Spring Boot 4、Netty、RabbitMQ、Micrometer、JUnit 5、AssertJ、Maven、Docker Compose、Prometheus。

---

### Task 1: 定义稳定 TCP 插件 SDK

**Files:**
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/tcp/TcpFrameDefinition.java`
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/tcp/TcpProtocolPlugin.java`
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/tcp/TcpProtocolSession.java`
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/tcp/TcpDecodedMessage.java`
- Create: `mine-protocol-sdk/src/main/java/com/bosco/mine/protocol/sdk/tcp/TcpProtocolException.java`
- Test: `mine-protocol-sdk/src/test/java/com/bosco/mine/protocol/sdk/tcp/TcpProtocolContractTest.java`

**Steps:**
1. 写失败测试，要求帧长度字段只允许 1、2、4 字节，最大帧 32～1 MiB，属性深复制不可修改，错误码必须来自固定枚举。
2. 运行 `./mvnw -pl mine-protocol-sdk -am -Dtest=TcpProtocolContractTest test`，确认因类型缺失失败。
3. 实现最小不可变 API；所有公开类型和方法写中文 Javadoc，不暴露 Netty/Spring 类型。
4. 重跑定向测试，确认 GREEN。
5. 提交 `feat(protocol): 定义稳定 TCP 二进制插件接口`。

### Task 2: 实现 SPI 注册与兼容门禁

**Files:**
- Create: `mine-protocol-engine/src/main/java/com/bosco/mine/protocol/engine/tcp/TcpProtocolPluginRegistry.java`
- Test: `mine-protocol-engine/src/test/java/com/bosco/mine/protocol/engine/tcp/TcpProtocolPluginRegistryTest.java`

**Steps:**
1. 写失败测试，覆盖标准 ServiceLoader、构造注入、协议键格式、重复键、空插件、API 主版本不兼容和查询不存在。
2. 运行 `./mvnw -pl mine-protocol-engine -am -Dtest=TcpProtocolPluginRegistryTest test`，确认 RED。
3. 实现不可变注册表；加载失败使用固定异常且不激活半份注册结果。
4. 运行 SDK 与 engine 全部测试，确认 GREEN。
5. 提交 `feat(protocol): 注册受控 TCP 协议插件`。

### Task 3: 交付矿卡二进制示例插件

**Files:**
- Modify: `pom.xml`
- Create: `examples/protocol-plugins/haul-truck/pom.xml`
- Create: `examples/protocol-plugins/haul-truck/src/main/java/com/bosco/mine/example/protocol/haultruck/HaulTruckProtocolPlugin.java`
- Create: `examples/protocol-plugins/haul-truck/src/main/java/com/bosco/mine/example/protocol/haultruck/HaulTruckProtocolSession.java`
- Create: `examples/protocol-plugins/haul-truck/src/main/java/com/bosco/mine/example/protocol/haultruck/Crc16Ccitt.java`
- Create: `examples/protocol-plugins/haul-truck/src/main/resources/META-INF/services/com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin`
- Test: `examples/protocol-plugins/haul-truck/src/test/java/com/bosco/mine/example/protocol/haultruck/HaulTruckProtocolSessionTest.java`
- Create: `examples/protocols/tcp-binary/haul-truck/README.md`

**Steps:**
1. 写黄金帧失败测试，覆盖字段解析、十进制缩放、ACK、CRC、截断、魔数、版本、类型、声明长度、ASCII 身份和字段范围。
2. 运行 `./mvnw -pl examples/protocol-plugins/haul-truck -am test`，确认 RED。
3. 实现 CRC16-CCITT-FALSE、严格解码、ACK 编码和 ServiceLoader 文件。
4. 在 README 固化示例 HEX、字段表、模拟器发送步骤和非厂商协议声明。
5. 运行模块与 SPI 加载测试，确认 GREEN。
6. 提交 `feat(protocol): 提供矿卡二进制协议样例`。

### Task 4: 建立 TCP 可信身份与统一消息转换

**Files:**
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/session/DeviceSessionRegistry.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/TcpMessageNormalizer.java`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/session/DeviceSessionRegistryVersionTest.java`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/tcp/TcpMessageNormalizerTest.java`

**Steps:**
1. 写失败测试，要求 `productKey + deviceCode` 唯一反查、TCP 协议版本匹配、UUIDv7、不可变属性、固定接收时间和发布关联字段。
2. 运行定向测试确认 RED。
3. 为完整身份快照增加原子 TCP 索引，实现统一消息转换；不能信任帧内 platform deviceId。
4. 运行 Gateway 单元测试确认 GREEN。
5. 提交 `feat(gateway): 绑定 TCP 报文可信设备身份`。

### Task 5: 实现 Netty 拆帧、会话和可靠 ACK

**Files:**
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/TcpDeviceChannelHandler.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/TcpChannelInitializer.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/TcpConnectionLimiter.java`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/tcp/TcpDeviceChannelHandlerTest.java`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/tcp/TcpChannelInitializerTest.java`

**Steps:**
1. 用 EmbeddedChannel 写失败测试，覆盖半包、粘包、超长帧、读空闲、首帧绑定、身份切换、成功 confirm 后 ACK 和发布失败关闭且无 ACK。
2. 运行定向测试确认 RED。
3. 用 SDK 帧定义配置 `LengthFieldBasedFrameDecoder`，实现每连接插件会话和串行发布；固定错误关闭连接。
4. 运行定向与 Gateway 全部单元测试确认 GREEN。
5. 提交 `feat(gateway): 接入可靠 TCP 二进制会话`。

### Task 6: 装配监听器、TLS、指标和运行生命周期

**Files:**
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/bootstrap/GatewayTcpProperties.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/bootstrap/GatewayTcpConfiguration.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/TcpGatewayServer.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/tcp/TcpGatewayMetrics.java`
- Modify: `mine-gateway/src/main/resources/application.yml`
- Test: matching properties, lifecycle, TLS and loopback tests

**Steps:**
1. 写失败测试覆盖默认关闭、端口/连接/空闲/帧上限、未知插件、TLS 证书私钥成对配置、随机端口启动和优雅停止。
2. 实现显式配置、Netty event loop 生命周期、可选 PEM TLS、低基数指标和健康信息。
3. 用本机回环 Socket 验证拆帧到发布再到 ACK 的完整顺序。
4. 运行 Gateway 测试确认 GREEN。
5. 提交 `feat(gateway): 运行受控 TCP 二进制监听器`。

### Task 7: 部署、告警、文档与非现场验收

**Files:**
- Modify: `mine-gateway/pom.xml`
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/env/standalone.env.example`
- Modify: `deploy/tests/compose-config.sh`
- Modify: `deploy/config/prometheus/rules/platform.yml`
- Modify: `docs/03-device-access-protocol.md`
- Modify: `docs/09-deployment-operations.md`
- Modify: `docs/10-testing-quality-gates.md`
- Modify: `docs/12-roadmap-and-codex-workflow.md`
- Create: `docs/runbooks/tcp-binary-gateway.md`
- Create: `docs/reports/phase-2-tcp-binary-acceptance.md`

**Steps:**
1. 先写 Compose/Prometheus 失败检查，要求应用默认关闭、单机显式启用、端口/连接/空闲/TLS 文件边界和固定低基数告警。
2. 把示例插件以 runtime 依赖装入 Gateway，单机暴露受控 TCP 端口；生产 TLS 仍须显式证书。
3. 更新架构、运行手册、示例模拟器步骤、升级回退和明确延期门禁。
4. 运行 `./mvnw -pl mine-protocol-sdk,mine-protocol-engine,mine-protocol-example-haul-truck,mine-gateway -am test`。
5. 运行 `bash deploy/tests/compose-config.sh`、Prometheus 校验、`bash scripts/verify.sh` 和 `git diff --check`。
6. 提交 `docs(protocol): 完成 TCP 二进制纵向样例验收`。
