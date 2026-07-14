# 阶段 1 首个 MQTT 设备闭环 Implementation Plan

> **For Codex:** REQUIRED SKILL: Use executing-plans to implement this plan task-by-task.

**Goal:** 用一个真实 MQTT 设备和一个可重复模拟器完成上报、标准化、历史/当前值、实时页面和命令回复的端到端闭环。

**Architecture:** 设备连接 RabbitMQ /mine-device；mine-gateway 将厂商消息转换为统一消息并发布 /mine-platform；mine-telemetry 幂等处理并写 TimescaleDB/Redis；mine-server 提供管理和命令 API；mine-web 通过 REST + WebSocket 展示。

**Tech Stack:** 阶段 0 技术栈、RabbitMQ MQTT plugin、Spring AMQP、TimescaleDB、Redis、Vitest/Playwright、Testcontainers。

---

## 前置材料

- 真实 productKey 和 deviceCode 编码规则。
- MQTT 版本、clientId、用户名/密码。
- 上行主题和至少 20 条脱敏真实报文。
- 心跳、在线/离线规则。
- 一个安全的只读/查询类命令及 ACK 报文。

如果真实设备材料未到，先使用 examples/device-simulator，但阶段退出前必须替换为真实设备验收。

### Task 1：产品、设备和凭据 schema

**Files:**

- Create: mine-server/src/main/resources/db/migration/V3__create_device_product.sql
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/model/Product.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/model/Device.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/repository/DeviceMapper.java
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/device/DeviceRepositoryIT.java

**Steps:**

1. 写 Testcontainers 失败测试：productKey/deviceCode 唯一、归档不删除、凭据无明文列。
2. 运行 ./mvnw -pl mine-server -am verify，确认 FAIL。
3. 写 V3 migration 和最小 Mapper/model。
4. 再运行，确认 PASS。
5. 提交：

~~~bash
git commit -am "feat(device): 创建设备产品与凭据模型"
~~~

### Task 2：设备管理 API

**Files:**

- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/controller/DeviceController.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceCommandService.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceQueryService.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/dto/
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/device/controller/DeviceControllerTest.java

**Steps:**

1. 写 401、403、201、重复编码 409、归档和审计失败测试。
2. 实现最小 API。
3. 运行 Controller + integration 测试。
4. 更新 OpenAPI。
5. 提交：

~~~bash
git commit -am "feat(device): 新增产品与设备管理接口"
~~~

### Task 3：设备凭据下发到 RabbitMQ

**Files:**

- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceCredentialService.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/integration/RabbitUserAdminClient.java
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/device/DeviceCredentialIT.java
- Modify: deploy/config/rabbitmq/rabbitmq.conf

**Steps:**

1. 写测试：创建后 secret 仅返回一次；设备只能访问自己的 topic；轮换旧 secret 失效。
2. 运行并确认 FAIL。
3. 使用 RabbitMQ HTTP API/受控管理接口实现，不调用 shell 拼接用户输入。
4. 运行 MQTT 连接与越权测试。
5. 提交：

~~~bash
git commit -am "feat(device): 实现MQTT设备凭据与主题权限"
~~~

### Task 4：创建设备模拟器

**Files:**

- Create: examples/device-simulator/pom.xml
- Create: examples/device-simulator/src/main/java/com/bosco/mine/examples/simulator/SimulatorApplication.java
- Create: examples/device-simulator/src/main/java/com/bosco/mine/examples/simulator/Scenario.java
- Create: examples/device-simulator/src/test/java/com/bosco/mine/examples/simulator/ScenarioTest.java
- Create: examples/device-simulator/scenarios/basic-mqtt.yaml

**Steps:**

1. 写失败测试：固定 seed 产生稳定 sequence、occurredAt、重复、乱序和断线场景。
2. 实现 MQTT 发布/订阅和 command ACK。
3. 运行单元测试和本地 Broker smoke。
4. 文档写启动命令，不内置生产密码。
5. 提交：

~~~bash
git commit -am "feat(examples): 新增可重复MQTT设备模拟器"
~~~

### Task 5：gateway MQTT 上行消费

**Files:**

- Create: mine-gateway/src/main/java/com/bosco/mine/gateway/mqtt/DeviceMessageConsumer.java
- Create: mine-gateway/src/main/java/com/bosco/mine/gateway/session/DeviceSessionRegistry.java
- Create: mine-gateway/src/main/java/com/bosco/mine/gateway/normalize/DeviceMessageNormalizer.java
- Test: mine-gateway/src/test/java/com/bosco/mine/gateway/mqtt/DeviceMessageConsumerIT.java

**Steps:**

1. 写 RabbitMQ MQTT → AMQP 消费失败集成测试。
2. 断言 topic/device 身份匹配、大小限制、统一 messageId 和 publisher confirm。
3. 实现最小 JSON 报文适配，不先做通用协议编辑器。
4. 运行集成测试。
5. 提交：

~~~bash
git commit -am "feat(gateway): 打通MQTT上行统一消息"
~~~

### Task 6：遥测时序 migration

**Files:**

- Create: mine-telemetry/src/main/resources/db/migration/V1__create_telemetry_hypertables.sql
- Create: mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryMigrationIT.java

**Steps:**

1. 写 TimescaleDB 容器失败测试：扩展、telemetry_numeric、location_point、hypertable 和索引。
2. 实现 migration。
3. 运行空库和重复启动测试。
4. 用 OSS 镜像验证 license 模式。
5. 提交：

~~~bash
git commit -am "feat(telemetry): 创建遥测与位置时序表"
~~~

### Task 7：telemetry 幂等和迟到处理

**Files:**

- Create: mine-telemetry/src/main/java/com/bosco/mine/telemetry/pipeline/TelemetryPipeline.java
- Create: mine-telemetry/src/main/java/com/bosco/mine/telemetry/dedup/DeduplicationPolicy.java
- Create: mine-telemetry/src/main/java/com/bosco/mine/telemetry/time/LateDataPolicy.java
- Test: mine-telemetry/src/test/java/com/bosco/mine/telemetry/pipeline/TelemetryPipelineTest.java

**Steps:**

1. 参数化失败测试覆盖重复、乱序、bootId 重启、无 sequence 和时间不可信。
2. 实现纯函数决策对象，先不接数据库。
3. 断言迟到数据 STORE_HISTORY=true、UPDATE_LATEST=false。
4. 运行测试。
5. 提交：

~~~bash
git commit -am "feat(telemetry): 实现幂等乱序与迟到判定"
~~~

### Task 8：Timescale 批写和 Redis 最新值

**Files:**

- Create: mine-telemetry/src/main/java/com/bosco/mine/telemetry/storage/TelemetryBatchWriter.java
- Create: mine-telemetry/src/main/java/com/bosco/mine/telemetry/latest/LatestValueRepository.java
- Create: mine-telemetry/src/main/java/com/bosco/mine/telemetry/consumer/NormalizedTelemetryConsumer.java
- Test: mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryStorageIT.java

**Steps:**

1. 写真实 Timescale/Redis/Rabbit 集成失败测试。
2. 覆盖正常、重复重投、Redis 先成功后 DB 失败、迟到不覆盖。
3. 实现手工 ACK 和批量写。
4. 断言 eventId 业务幂等。
5. 提交：

~~~bash
git commit -am "feat(telemetry): 实现历史批写与最新值缓存"
~~~

### Task 9：最新值和历史 API

**Files:**

- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/controller/DeviceTelemetryController.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceTelemetryQueryService.java
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/device/controller/DeviceTelemetryControllerTest.java
- Update: docs/openapi/mine-api.yaml

**Steps:**

1. 写权限、时间范围上限、分页/聚合和质量字段测试。
2. 实现 Redis latest + Timescale history 查询适配。
3. Redis miss 回源最近历史。
4. 运行测试和 OpenAPI 兼容检查。
5. 提交：

~~~bash
git commit -am "feat(device): 新增设备实时值与历史查询"
~~~

### Task 10：统一 WebSocket 和 RealtimeHub

**Files:**

- Create: mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeWebSocketHandler.java
- Create: mine-server/src/test/java/com/bosco/mine/server/bootstrap/websocket/RealtimeWebSocketIT.java
- Create: mine-web/src/realtime/RealtimeHub.ts
- Test: mine-web/src/realtime/RealtimeHub.test.ts

**Steps:**

1. 后端测试 AUTH/SUBSCRIBE/UNSUBSCRIBE/RESYNC/限额。
2. 前端 fake timer 测试重连、引用计数、sequence gap、批量通知。
3. 实现先 SNAPSHOT 后 DATA。
4. 运行前后端测试。
5. 提交：

~~~bash
git commit -am "feat(realtime): 实现统一WebSocket实时通道"
~~~

### Task 11：设备前端页面

**Files:**

- Create: mine-web/src/modules/device/pages/product-list/
- Create: mine-web/src/modules/device/pages/device-list/
- Create: mine-web/src/modules/monitoring/pages/device-monitor/
- Create: mine-web/src/modules/monitoring/components/NumericCard.vue
- Test: 对应 Vitest 文件
- Test: mine-web/e2e/device-monitor.spec.ts

**Steps:**

1. 写列表、创建、空状态、错误状态和动态卡片失败测试。
2. 生成 OpenAPI 类型并包装业务 API。
3. 实现最小 product/device 页面和实时数值卡。
4. Playwright 断言模拟器上报后页面更新。
5. 提交：

~~~bash
git commit -am "feat(frontend): 新增设备管理与实时监控"
~~~

### Task 12：命令表、状态机和 outbox

**Files:**

- Create: mine-server/src/main/resources/db/migration/V4__create_command_tables.sql
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/command/model/CommandStatus.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/command/model/CommandInstance.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/command/service/CommandService.java
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/command/CommandStateMachineTest.java
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/command/CommandOutboxIT.java

**Steps:**

1. 写所有允许/禁止状态迁移失败测试。
2. 写命令+outbox 同事务测试。
3. 实现 LOW 风险示例命令；幂等键重复返回原命令。
4. 运行测试。
5. 提交：

~~~bash
git commit -am "feat(command): 实现命令状态机与可靠出站"
~~~

### Task 13：gateway 命令下行和设备 ACK

**Files:**

- Create: mine-gateway/src/main/java/com/bosco/mine/gateway/command/CommandDispatchConsumer.java
- Create: mine-gateway/src/main/java/com/bosco/mine/gateway/command/MqttCommandPublisher.java
- Create: mine-gateway/src/main/java/com/bosco/mine/gateway/command/CommandAckConsumer.java
- Test: mine-gateway/src/test/java/com/bosco/mine/gateway/command/CommandRoundTripIT.java

**Steps:**

1. 写命令 → MQTT → 模拟器 ACK → 状态事件失败测试。
2. 断言 PUBACK 只推进 SENT，业务 ACK 才推进 DEVICE_RECEIVED/SUCCEEDED。
3. 实现 TTL 和 timeout。
4. 运行集成测试。
5. 提交：

~~~bash
git commit -am "feat(gateway): 打通MQTT命令与设备回复"
~~~

### Task 14：命令前端

**Files:**

- Create: mine-web/src/modules/command/pages/command-center/
- Create: mine-web/src/modules/command/components/CommandTimeline.vue
- Test: mine-web/src/modules/command/components/CommandTimeline.test.ts
- Test: mine-web/e2e/command-round-trip.spec.ts

**Steps:**

1. 写参数、二次确认、重复提交和状态时间线测试。
2. 实现 LOW 风险命令最小页。
3. Playwright 完成成功、拒绝和超时三种路径。
4. 提交：

~~~bash
git commit -am "feat(frontend): 新增命令下发与状态时间线"
~~~

### Task 15：弱网端到端

**Files:**

- Create: examples/device-simulator/scenarios/weak-network.yaml
- Create: mine-telemetry/src/test/java/com/bosco/mine/telemetry/e2e/WeakNetworkE2EIT.java
- Create: docs/reports/phase-1-weak-network.md

**Steps:**

1. 模拟重复、乱序、10 分钟断网、补传和设备重启。
2. 断言历史数、最新值、gap 事件和业务副作用。
3. 修复失败直到所有断言通过。
4. 保存指标和结果。
5. 提交：

~~~bash
git commit -am "test(telemetry): 增加弱网补传端到端验证"
~~~

### Task 16：阶段验收和 24 小时真实设备运行

**Files:**

- Create: docs/reports/phase-1-acceptance.md
- Create: docs/runbooks/device-onboarding.md
- Update: docs/contracts/

**Steps:**

1. 运行全仓 verify。
2. 清洁环境执行单机离线安装。
3. 接入真实设备并运行 24 小时。
4. 记录消息率、失败率、延迟、资源、断线和未知报文。
5. 执行命令闭环。
6. 列出遗留问题并决定是否进入阶段 2。
7. 提交：

~~~bash
git commit -am "docs(report): 完成首个设备闭环验收"
~~~

## 阶段 1 最终命令

~~~bash
bash scripts/verify.sh
bash deploy/tests/standalone-smoke.sh
./mvnw verify
pnpm --dir mine-web test
pnpm --dir mine-web exec playwright test
~~~

Expected: 全部 PASS，真实设备报告无未解释的数据丢失或重复业务副作用。
