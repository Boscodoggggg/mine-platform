# Telemetry Storage And Cache Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现 RabbitMQ 标准化遥测的手工确认消费、TimescaleDB 幂等批写和 Redis 最新值原子更新，并为后续选择性多级缓存建立清晰边界。

**Architecture:** gateway 输出内部 UUID 设备标识；mine-telemetry 将消息展开为数值遥测点，Redis Lua 原子更新设备级最新值，TimescaleDB Inbox 与历史行在同一事务中写入。消费者采用至少一次投递、手工 ACK 和数据库业务唯一约束，Redis 只保存可重建读模型。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring AMQP、Spring Data Redis、JDBC、TimescaleDB OSS、RabbitMQ、Redis、JUnit 6、Testcontainers。

---

### Task 1: 补强 gateway 内部设备标识

**Files:**
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/session/DeviceSessionRegistry.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/session/DeviceIdentitySnapshotLoader.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/normalize/DeviceMessageNormalizer.java`
- Test: `mine-gateway/src/test/java/com/bosco/mine/gateway/normalize/DeviceMessageNormalizerTest.java`

1. 先写失败测试：身份快照同时包含 UUID `deviceId` 和自然编码 `deviceCode`，报文按 `deviceCode` 校验，输出 UUID 字符串。
2. 运行 `./mvnw -B -ntp -pl :mine-gateway -am -Dtest=DeviceMessageNormalizerTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认因身份结构缺字段失败。
3. 最小修改身份记录、JSON 加载和标准化器。
4. 重跑测试并确认通过。

### Task 2: 增加遥测 Inbox 迁移

**Files:**
- Create: `mine-telemetry/src/main/resources/db/migration/V3__create_telemetry_inbox.sql`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryMigrationIT.java`

1. 先扩展迁移测试，断言 `telemetry_inbox`、eventId 主键、可靠业务幂等部分唯一索引以及表/字段中文注释。
2. 运行迁移测试并确认缺表失败。
3. 创建 Inbox 表，字段至少包含 `event_id`、`device_id`、`message_type`、`boot_id`、`sequence`、`received_at`、`processed_at`。
4. 为表和每个字段添加中文 `COMMENT`，重跑真实 TimescaleDB 测试。

### Task 3: 实现 Redis 最新值原子仓储

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/pipeline/TelemetrySample.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/latest/LatestValueRepository.java`
- Test: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryStorageIT.java`

1. 使用真实 Redis 写失败测试：新值写入、迟到拒绝、相同时间稳定决胜、重复写无副作用。
2. 运行测试并确认仓储类不存在。
3. 用 Redis Hash 和 Lua 实现 `updateIfNewer`，比较和写入必须在一个脚本内完成。
4. latest key 不设置统一 TTL；值 JSON 保存 value、occurredAt、receivedAt、quality、eventId、bootId 和 sequence。
5. 重跑 Redis 测试。

### Task 4: 实现 TimescaleDB Inbox 与历史批写

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/storage/TelemetryBatchWriter.java`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryStorageIT.java`

1. 先写失败测试：正常批写、相同 eventId 重投、相同可靠业务键但新 eventId 重投、事务失败后 Inbox 回滚。
2. 使用 JDBC 事务先执行 `INSERT ... ON CONFLICT DO NOTHING RETURNING` 占用 Inbox。
3. 只对成功占用 Inbox 的事件使用 `PreparedStatement.addBatch()` 写入属性行。
4. 任一异常回滚 Inbox 和历史；重跑真实 TimescaleDB 测试。

### Task 5: 实现 RabbitMQ 批消费和手工 ACK

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/consumer/NormalizedTelemetryConsumer.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/bootstrap/TelemetryInfrastructureConfiguration.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/bootstrap/TelemetryInfrastructureProperties.java`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryStorageIT.java`

1. 先写真实 RabbitMQ + Redis + TimescaleDB 失败测试，发布标准化事件并启动手工 ACK 批监听器。
2. 覆盖正常消费、重复重投、Redis 已成功后数据库失败再恢复、迟到不覆盖。
3. 消费者解析 `MessageEnvelope<UnifiedDeviceMessage>`，只把数值属性展开成 `TelemetrySample`。
4. 先原子更新 Redis，再事务批写 TimescaleDB；全部成功逐条 ACK，失败逐条 NACK 并重新入队。
5. 限制 batch size、prefetch 和 max wait，禁止无界缓冲。

### Task 6: 完成依赖、配置和现场部署

**Files:**
- Modify: `mine-telemetry/pom.xml`
- Modify: `mine-telemetry/src/main/resources/application.yml`
- Modify: `deploy/scripts/rabbitmq-bootstrap.sh`
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/secrets.example/README.md`

1. 增加 Spring AMQP、Spring Data Redis 和相应 Testcontainers 测试依赖。
2. 为 telemetry 建立 RabbitMQ 只读最小权限账号与 secret。
3. Compose 注入 TimescaleDB、Redis 和 RabbitMQ 参数，并增加 Redis 健康依赖。
4. 运行 `docker compose config` 和脚本语法检查。

### Task 7: 验证和提交

1. 运行 `./mvnw -B -ntp -pl :mine-telemetry -am verify`。
2. 运行 `./mvnw -B -ntp verify`。
3. 运行 `git diff --check`，确认仅包含 Task 8 相关文件。
4. 提交：`feat(telemetry): 实现历史批写与最新值缓存`。
