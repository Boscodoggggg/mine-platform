# 05 消息、遥测与数据架构

## 1. RabbitMQ 的作用

RabbitMQ 不只是给 MQTT 设备连接。它还负责在 gateway、telemetry 和 server 之间缓冲、解耦和可靠传递事件。

如果 telemetry 暂时重启，gateway 不需要同步等待它；消息由队列保留，telemetry 恢复后继续消费。这样连接接入、数据计算和业务 API 可以独立扩缩和发布。

## 2. 单集群双 vhost

~~~mermaid
flowchart TB
    subgraph Rabbit["RabbitMQ 三节点集群"]
        DV["/mine-device\nMQTT设备域"]
        PV["/mine-platform\n内部事件域"]
    end
    Devices["设备"] --> DV
    DV --> Gateway["mine-gateway"]
    Gateway --> PV
    PV --> Telemetry["mine-telemetry"]
    PV --> Server["mine-server"]
    Server --> PV
    PV --> Gateway
~~~

两个 vhost 是权限、命名和配额隔离，不是物理故障隔离。RabbitMQ 集群级磁盘或内存告警仍可能同时影响二者。

### 2.1 MQTT 遥测跨 vhost 链路

阶段 1 的实际链路为：

~~~text
设备 MQTT publish
→ /mine-device 的 amq.topic
→ mine.gateway.uplink.q
→ mine-gateway 身份校验和 JSON 标准化
→ publisher confirm
→ /mine-platform 的 mine.telemetry.x
→ mine.telemetry.q
~~~

gateway 使用两个独立服务账号和连接：`mine-gateway-device` 只能读取设备上行队列，
`mine-gateway-platform` 只能写内部遥测交换机。不得让一个账号跨两个 vhost 获得读写全权限。

设备 topic 中的 `clientId` 是平台签发的凭据身份，不等于厂商 `deviceCode`。gateway 通过可信身份快照完成
`productKey + clientId → deviceId` 映射，并校验报文自报的 `productKey/deviceCode`。未知身份、身份冒用、
非法结构和超过 64 KiB 的报文拒绝重试并进入 `mine.device.dead-letter.q`；内部事件未获 Broker confirm 时，
原始消息不 ACK 并重新投递。

阶段 1 使用 `deploy/config/gateway/device-identities.json` 作为启动和现场兜底快照，默认每 5 秒原子热刷新，
文件更新失败时保留上一有效版本。后续前台“发布配置”通过配置事件调用同一注册表替换入口，不能要求重启 gateway；
快照文件不作为最终配置主数据源。

## 3. 内部交换机与队列

建议交换机：

| exchange | 类型 | 用途 |
|---|---|---|
| mine.telemetry.x | topic | 统一遥测、位置和设备事件 |
| mine.business.x | topic | 告警、绑定、作业等业务事件 |
| mine.command.x | topic | 命令下发和状态 |
| mine.config.x | topic | 配置发布和节点状态 |
| mine.dlx | topic | 死信 |

建议路由键：

~~~text
telemetry.normalized.{productKey}
location.normalized.{productKey}
device.online
device.offline
alarm.raised
alarm.recovered
operation.completed
command.dispatch.{productKey}
command.status.{status}
config.release.gateway
config.release.telemetry
config.node.loaded
~~~

队列由消费者拥有并命名：

~~~text
mine.gateway.uplink.q
mine.telemetry.q
telemetry.location.q
server.device-event.q
server.alarm-event.q
gateway.command.q
gateway.config.q
telemetry.config.q
integration.forwarding.q
~~~

## 4. 队列类型

- 命令、配置、告警和关键业务事件：Quorum Queue。
- MQTT 持久会话队列：按 RabbitMQ MQTT 插件能力和设备会话策略配置，不把所有高 churn 客户端强制改为 quorum。
- 高频遥测：一期先使用经过容量设计的持久队列；当基准测试证明普通队列成为瓶颈时，再采用 RabbitMQ Streams 和专用客户端。
- 死信队列：Quorum Queue，设置容量和告警，不允许无限增长。

队列类型必须由负载模型决定，不因为“更可靠”就把所有消息复制三份。

## 5. 消息信封

所有内部消息共享信封：

~~~json
{
  "eventId": "uuid-v7",
  "eventType": "telemetry.normalized",
  "schemaVersion": 1,
  "occurredAt": "2026-07-14T08:00:00Z",
  "publishedAt": "2026-07-14T08:00:02Z",
  "producer": "mine-gateway",
  "traceId": "trace-id",
  "correlationId": "command-or-session-id",
  "partitionKey": "device-001",
  "tenantScope": "mine-local",
  "payload": {}
}
~~~

约束：

- eventType 和 schemaVersion 决定反序列化。
- 新版本只能新增可选字段；删除/改语义需要新 schemaVersion。
- 消息不得直接序列化 JPA/MyBatis 实体。
- payload 有大小上限；大型文件只传 objectRef。
- 设备维度有序只在同一 partitionKey 和特定队列设计下保证，不宣称全局有序。

## 6. 发布可靠性

生产者：

- 开启 publisher confirm。
- publish 失败进行有限指数退避。
- mine-server 使用 outbox。
- gateway 无数据库事务，失败消息进入本地有界重试或令当前消费不 ACK。
- messageId/eventId 在重试中保持不变。
- gateway 标准化事件的 eventId 与 payload.messageId 使用同一个 UUIDv7，跨 vhost 重投时不得重新生成。

消费者：

- 处理完成并提交必要存储后手动 ACK。
- 可重试异常进入延迟重试队列。
- 不可重试异常进入 DLQ，附错误码和原始 eventId。
- 消费次数超过阈值不再热循环。
- 每个消费者实现 eventId 或业务键幂等。

## 7. 背压和过载

每条链路定义：

- prefetch。
- 单批大小和批次最大等待时间。
- 队列最大长度/字节数。
- 消息 TTL。
- 重试次数和退避。
- 降级或丢弃等级。

优先级：

1. 命令回复、告警、设备状态和作业事件。
2. 普通遥测。
3. 高频位置和调试原始数据。

当下游数据库变慢时，系统先降低 WebSocket 推送和原始采样，再对可降级高频数据采样；不得静默丢弃关键事件。

## 8. telemetry 处理流水线

~~~text
Rabbit消费
→ 信封/schema校验
→ 幂等判断
→ 字段映射
→ 数据质量
→ 迟到/乱序判断
→ 最新值判定
→ 告警/派生/围栏计算
→ Timescale批写
→ Redis最新值
→ 业务事件
→ WebSocket发布
→ ACK
~~~

一个消息可以部分字段有效、部分无效。质量结果应按属性记录，避免一个坏字段丢弃整帧。

## 9. TimescaleDB 数据布局

### 9.1 通用历史

按值类型拆表，避免把所有值存字符串：

~~~text
telemetry_numeric(
  time, device_id, product_key, property_id,
  value_double, quality, sequence, event_id, received_at
)

telemetry_text(
  time, device_id, product_key, property_id,
  value_text, quality, sequence, event_id, received_at
)

telemetry_boolean(...)
telemetry_event(...)
~~~

建议索引：

- device_id + property_id + time DESC。
- product_key + property_id + time DESC（按查询需要）。
- event_id 唯一或短期去重索引，具体结合写入性能测试。

### 9.2 UWB 位置专表

~~~text
location_point(
  time, device_id, tag_id,
  x, y, z,
  speed, heading, quality,
  coordinate_system_id,
  boot_id, sequence, event_id, received_at
)
~~~

位置点不走通用 property 行模型，否则单点会膨胀成多行并降低轨迹查询效率。

### 9.3 业务事件

告警、命令、绑定和运营记录存 PostgreSQL 业务表。Timescale 可保存其高频计算输入，但不能代替业务状态机。

## 10. 批量写入

- 按表、时间桶和数据类型聚合批次。
- 满足 batchSize 或 maxWait 后写入。
- 写入失败可重试，eventId 保持不变。
- 单批失败时能定位坏记录并隔离，不能无限二分拖垮消费。
- JDBC COPY 或 batch insert 需要通过基准测试选择。
- ACK 必须在持久化成功后；如果 Redis 先写成功而 DB 失败，重试必须幂等。

## 11. Redis 数据

Redis 只保存可重建数据：

~~~text
mine:latest:{deviceId}             当前属性哈希
mine:latest-time:{deviceId}        每属性最近发生时间
mine:presence:{deviceId}           在线会话与过期
mine:config:{nodeId}               节点当前配置版本
mine:ws:subscription:{sessionId}   短期订阅信息
~~~

规则：

- 迟到消息不覆盖更晚 latest-time。
- Redis 丢失后从新消息和 Timescale 最近值重建。
- 不把用户、设备档案、命令结果只存 Redis。
- key 必须有清晰 TTL 和命名空间。

## 12. 数据时间语义

- occurredAt：设备声明的发生时间。
- receivedAt：平台接收时间。
- processedAt：telemetry 完成处理时间。
- storedAt：写入数据库时间。

历史查询默认使用 occurredAt；延迟监控使用 receivedAt - occurredAt。设备时钟不可信时标记 TIME_UNTRUSTED，并可按策略使用 receivedAt，但不得悄悄改写原时间。

## 13. 数据保留与聚合

- 当前值：Redis 中持续更新，设备归档后按策略清理。
- 明细遥测：按数据等级和产品/组设定。
- UWB：近期保留原粒度，较老数据可降采样为轨迹段或分钟点。
- 原始报文：正常采样、异常短期全量。
- 告警/命令/审计：按法规和矿区要求长期保留。

清理任务：

- 先形成影响预览。
- 按 Timescale chunk/时间范围删除。
- 有速率限制、进度、失败重试和指标。
- 禁止管理员在页面直接执行自由 SQL。

## 14. 可观测指标

- publish confirm 延迟和失败率。
- 每队列 ready/unacked、最老消息年龄。
- 消费成功、重试、DLQ 和幂等命中。
- 解析失败、CRC 错误、未知消息。
- 迟到、乱序、sequence gap。
- 批写大小、耗时、失败、Timescale 延迟。
- Redis 写入失败和 latest 拒绝覆盖次数。
- 每个 pipeline 阶段耗时，但 deviceId 不作为 Prometheus 标签。
