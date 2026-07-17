# Phase 2 数据质量策略运行消费实施计划

> 本计划按 TDD 执行：每个任务先补失败测试，再写最小实现，定向验证后提交。真实现场与容量长稳验证继续延期。

**目标：** 交付可发布、可分发、可回退、可追溯的数据质量策略运行链路。

**架构：** 复用三级有效策略解析器；`mine-server` 用事务 Outbox 发布版本化策略变更，
`mine-telemetry` 持久化最后正确运行镜像并在入库前执行规则，质量证据与遥测事件同事务提交。

---

## 任务 1：注册数据质量策略类型

**文件：**

- 新增：`mine-device-model/src/main/java/com/bosco/mine/model/strategy/DataQualityStrategyDefinition.java`
- 新增：`mine-device-model/src/test/java/com/bosco/mine/model/strategy/DataQualityStrategyDefinitionTest.java`
- 修改：`mine-server/src/main/java/com/bosco/mine/server/modules/strategy/service/StrategyConfigurationService.java`
- 修改：`mine-server/src/test/java/com/bosco/mine/server/modules/strategy/service/StrategyConfigurationServiceTest.java`
- 新增：`mine-server/src/main/resources/db/migration/V23__enable_data_quality_strategy.sql`
- 修改：`mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

步骤：

1. 先写字段类型、范围、动态属性名、十进制规范化和上下限矛盾测试。
2. 实现白名单定义，并让服务注册 `DATA_QUALITY`。
3. 发布冲突预检除同优先级冲突外，还要校验合并后的最终语义。
4. 扩展数据库类型约束并验证迁移。
5. 执行 `./mvnw -B -ntp -pl :mine-device-model,:mine-server -am test`。

## 任务 2：可靠发布运行修订

**文件：**

- 新增：`mine-messaging/src/main/java/com/bosco/mine/messaging/strategy/StrategyRuntimeChangedPayload.java`
- 修改：`mine-messaging/src/main/java/com/bosco/mine/messaging/topology/MineTopology.java`
- 新增：`mine-server` 策略运行快照、Outbox 写入与发布类及测试
- 修改：`mine-server` 策略发布、回滚和设备组变更服务
- 修改：`deploy/config/rabbitmq/definitions.json`
- 修改：`deploy/scripts/rabbitmq-bootstrap.sh`

步骤：

1. 先写消息契约、Outbox 原子写入和 Rabbit confirm 测试。
2. 为产品/策略类型生成单调运行修订并写轻量事件。
3. 将发布、回滚、成员、优先级和归档变化接入修订生成。
4. 配置独立交换机、队列和最小权限。
5. 执行消息、服务和部署脚本定向测试。

## 任务 3：Telemetry 最后正确策略镜像

**文件：**

- 新增：`mine-telemetry/src/main/resources/db/migration/V10__create_strategy_runtime_mirror.sql`
- 新增：`mine-telemetry` 策略事件解码、快照客户端、镜像仓储和消费者
- 修改：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/bootstrap/TelemetryStorageConfiguration.java`
- 修改：`mine-telemetry/src/main/resources/application.yml`
- 修改：`deploy/compose/compose-standalone.yml`
- 修改：Telemetry 迁移、单元和 RabbitMQ 集成测试

步骤：

1. 先写迁移注释完整性、低修订拒绝和幂等重投测试。
2. 复用服务令牌拉取有界快照；校验产品、类型、修订和 SHA-256。
3. 在 Telemetry 数据库原子激活策略镜像，加载失败保留最后正确版本。
4. 消费者使用手工 ACK：瞬时失败重投，永久契约错误拒绝。
5. 执行 `./mvnw -B -ntp -pl :mine-telemetry -am verify`。

## 任务 4：质量判定与证据

**文件：**

- 新增：`mine-telemetry/src/main/java/com/bosco/mine/telemetry/quality/*`
- 新增：`mine-telemetry/src/main/resources/db/migration/V11__create_quality_evidence.sql`
- 修改：`NormalizedTelemetryConsumer`、`TelemetryBatchWriter` 及对应测试

步骤：

1. 先写未来时间、陈旧、必填缺失、上下限和多规则质量合并测试。
2. 按设备批量读取策略，在生成 `TelemetrySample` 前执行规则。
3. 将证据与 Inbox、历史、持久最新值和摄取水位放进同一事务。
4. 保证重投幂等，且属性级 `BAD` 不污染同帧其他正常属性。
5. 增加低基数规则命中指标并执行 Telemetry 全模块验证。

## 任务 5：控制台、可观测和非现场门禁

**文件：**

- 修改：策略工作台 API、页面与中英文文案
- 修改：`deploy/config/prometheus/rules/*.yml`
- 修改：架构、策略、消息和验收报告文档

步骤：

1. 在现有工作台增加数据质量字段模板、校验提示和有效来源展示。
2. 增加策略加载失败和质量异常突增告警规则。
3. 执行前端 UI 治理、typecheck、lint、unit、build。
4. 执行 `bash scripts/verify.sh` 和 Chromium E2E。
5. 自审安全、失败语义、注释、文档和脏工作树边界后分批提交。

