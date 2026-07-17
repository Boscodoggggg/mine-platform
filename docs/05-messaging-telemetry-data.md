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

gateway 使用两个独立服务账号和连接：`mine-gateway-device` 只能读取设备遥测/命令 ACK 队列并向
`amq.topic` 写设备下行，`mine-gateway-platform` 只能读取 gateway 命令队列并写内部遥测/命令交换机。
不得让一个账号跨两个 vhost 获得读写全权限。
`mine-telemetry` 使用第三个服务账号，只能读取 `/mine-platform` 中的 `mine.telemetry.q`，并在完成持久化后写入
`mine.realtime.x`；不能声明业务队列、写其他交换机或读取其他业务队列。

设备 topic 中的 `clientId` 是平台签发的凭据身份，不等于厂商 `deviceCode`。gateway 通过可信身份快照完成
`productKey + clientId → deviceId` 映射，并校验报文自报的 `productKey/deviceCode`。未知身份、身份冒用、
非法结构和超过 64 KiB 的报文拒绝重试并进入 `mine.device.dead-letter.q`；内部事件未获 Broker confirm 时，
原始消息不 ACK 并重新投递。

阶段 1 使用 `deploy/config/gateway/device-identities.json` 作为启动和现场兜底快照，默认每 5 秒原子热刷新，
文件更新失败时保留上一有效版本。注册表要求版本单调递增：同版本同 SHA-256 视为幂等，低版本和同版本异内容
均拒绝；业务回滚必须把旧内容发布为更高的新版本。当前生效的 `version`、`sha256` 和 `deviceCount` 可从 gateway
内部管理端口 `/actuator/info` 查询。后续前台“发布配置”通过配置事件调用同一版本化注册表入口，不能要求重启
gateway；快照文件不作为最终配置主数据源。

### 2.2 MQTT 命令回环链路

~~~text
mine-server Outbox → mine.command.x / command.dispatch.{deviceId}
→ mine.gateway.command.q → mine-gateway 校验信封、TTL 和可信设备身份
→ /mine-device 的 amq.topic / mine.v1.{productKey}.{clientId}.down.command
→ 设备 QoS 1 接收并以 commandId 做业务幂等
→ mine.v1.{productKey}.{clientId}.up.command-ack
→ mine.gateway.command-ack.q → mine-gateway 校验主题身份
→ mine.command.x / command.status.{status}
→ mine.server.command-status.q
~~~

设备域 Broker 确认只产生 `SENT` 事实，不能解释为设备执行成功。只有设备业务 ACK 才能产生
`SUCCEEDED`、`REFUSED` 或 `FAILED`；终态事件同时携带 `deviceReceivedAt`。设备 ACK 可能与 `SENT` 事件并发到达
管理服务，状态消费者必须按业务证据幂等推进，不能假设跨消费者线程的到达顺序。命令 JSON 带绝对 `expiresAt`，
gateway 同时设置剩余消息 TTL；过期命令不得下发。

server 消费状态消息时，在同一 PostgreSQL 事务中先写 `sys_inbox_event`，再以 `FOR UPDATE` 锁定对应
`cmd_instance` 并推进状态，事务提交后才手工 ACK RabbitMQ。设备终态早于 `SENT` 到达时，以设备 ACK 作为更强业务
证据补齐 `sent_at`、`device_received_at` 和 `completed_at`；终态之后到达的 `SENT`、重复 ACK 或超时事件只登记 Inbox，
不得回退或覆盖已确认结果。数据库暂时失败则 NACK 重投，消息结构或身份不一致则拒绝并进入死信。

`SENT` 确认后，gateway 还会把统一格式的 `TIMEOUT` 状态事件以单消息 TTL 投递到
`mine.command.timeout.wait.q`。消息到达回复截止时间后，由 RabbitMQ 死信路由为 `command.status.timeout` 并进入
`mine.server.command-status.q`。这是一条持久化的一次性计时消息，不依赖 gateway 内存定时器，也不创建数据库定时
扫表；如果设备终态先到，server 状态机按命令当前终态幂等忽略迟到的 `TIMEOUT`。

`mine-telemetry` 对 RabbitMQ 采用至少一次投递和手工确认：先在 TimescaleDB 单事务中写入 Inbox、数值历史、
经过顺序策略筛选的持久最新值和 boot 状态，再用 Redis Lua 刷新实时加速层，最后通过 publisher confirm 发布
实时事件，三步全部成功后才 ACK。数据库失败时不得
更新 Redis；Redis 刷新失败时消息重新入队，数据库 Inbox 保证重投幂等，查询端可从持久最新值读模型恢复缓存。

实时最新值使用设备级 Redis Hash，不设置统一 TTL，防止活动设备键同时过期形成缓存雪崩。进程内一级缓存
不保存实时遥测，只预留给已发布的物模型、协议、字段映射与策略版本；实现时必须同时具备有界容量、带随机
抖动的短 TTL、发布事件主动失效和按 key 的 single-flight 回源。不存在的设备或属性先做权限与物模型校验，
必要时使用短 TTL 空值缓存，禁止无界回源 TimescaleDB。

阶段 1 的查询 API 已按 `deviceId + propertyId` 实现进程内 single-flight，等待上限 2 秒；一次请求的冷属性
合并为一条 TimescaleDB 查询。回源只读取持久最新值读模型，不能从包含乱序事实的历史表猜测最新值；同时恢复
当前 boot、最高序号和全部退役 boot。Redis Lua 原子回填避免查询线程用旧快照覆盖采集线程的新值；回填后必须重读 Redis 并
返回与采集线程竞争后的最终值。当前先校验设备台账、属性格式、属性数量和查询时间窗；物模型发布完成后再
增加属性存在性校验与带随机抖动的短 TTL 空值缓存，不能在物模型缺失时把任意属性永久缓存为空。
Redis 读取、顺序状态恢复或回填临时失败时，latest 接口直接返回持久最新值读模型结果并标记来源为
`TIMESCALEDB`，不能因为加速层故障把仍可读取的权威数据降级成 500。

历史写入事务通过全局单例水位行锁按提交顺序分配 `ingest_id`，并在同一事务更新已提交水位。历史分页首页返回当前已提交摄取水位
`watermark`，后续页必须原样传回；查询统一增加 `ingest_id <= watermark`。该边界不依赖设备时间或
平台接收时间，即使弱网数据已接收但因 RabbitMQ 积压、数据库重试延后提交，也不会插入已开始的分页数据集。
水位行锁以批次为单位持有，后续吞吐压测应优先增加单批事件数，不能移除该锁后继续宣称跨请求快照稳定。

从 V5 及更早版本升级时，V7 迁移先按 `telemetry_inbox.processed_at + event_id` 为存量事件生成确定性
`ingest_id`，再按照同一顺序重放顺序判定，回填当前 boot、最高序号、退役 boot 和各属性最新值。没有 Inbox
记录的早期历史以 `received_at + event_id` 作为兼容回放顺序。迁移完成且 `mine-server` 的
`telemetryQuery` readiness 验证必需表、摄取字段和 SELECT 权限后，查询入口才可进入就绪状态。

`telemetry_latest_state` 同时保存 `first_seen_at` 和 `last_received_at`：前者在首个通过验证的设备报文入库时固定，后者每次有效报文推进。管理端使用这两个平台接收时间判定有效首帧和数据新鲜度，不使用可能漂移或补传的设备发生时间判定在线状态。

## 3. 内部交换机与队列

建议交换机：

| exchange | 类型 | 用途 |
|---|---|---|
| mine.telemetry.x | topic | 统一遥测、位置和设备事件 |
| mine.business.x | topic | 告警、绑定、作业等业务事件 |
| mine.command.x | topic | 命令下发和状态 |
| mine.config.x | topic | 配置发布和节点状态 |
| mine.realtime.x | topic | 已持久化最新值通知各 mine-server 实例 |
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
command.dispatch.{deviceId}
command.status.{status}
config.release.gateway
config.release.telemetry
config.node.loaded
realtime.telemetry.{deviceId}
~~~

队列由消费者拥有并命名：

~~~text
mine.gateway.uplink.q
mine.telemetry.q
telemetry.location.q
server.device-event.q
server.alarm-event.q
mine.gateway.command.q
mine.server.command-status.q
mine.gateway.command-ack.q
mine.command.timeout.wait.q
gateway.config.q
telemetry.config.q
integration.forwarding.q
mine.realtime.server.{instanceId}.q
~~~

`mine.realtime.server.{instanceId}.q` 是例外：它是 classic、非持久、独占、自动删除队列，每个 server 实例一条，
使实时事件广播到所有实例，再由各实例只推送给本机 WebSocket 会话。实时事实已经存在 TimescaleDB/Redis 中，
浏览器断线后通过快照恢复，因此该队列不承担长期积压职责。

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
- Inbox 由消息到达触发并随业务事务写入，禁止为 Inbox 创建定时扫描任务。
- Outbox 与业务数据同事务写入，并在提交时发送唤醒通知；relay 按通知携带的 eventId 精确认领，不固定间隔扫描整表。
- 服务启动或 RabbitMQ 重连时只允许执行一次 `status + next_attempt_at` 索引上的有界恢复查询；运行期间失败事件按 eventId
  进入内存退避或 RabbitMQ 重试链路，不以高频扫表代替可靠性设计。
- mine-server 使用独立 `mine-server-command` 账号，只允许写 `mine.command.x`、读取
  `mine.server.command-status.q`，不访问其他交换机或队列；命令消息持久化并等待 publisher confirm 后才把 Outbox
  置为 `PUBLISHED`。
- 命令路由键使用 `command.dispatch.{deviceId}`，同一设备的下行消息共享分区键；命令 eventId 在全部尝试中保持不变。
- gateway 的下发队列只绑定 `command.dispatch.#`，管理服务状态队列只绑定 `command.status.#`，禁止用 `command.#`
  让 gateway 再次消费自己发布的状态事件。
- gateway 无数据库事务，失败消息进入本地有界重试或令当前消费不 ACK。
- messageId/eventId 在重试中保持不变。
- gateway 标准化事件的 eventId 与 payload.messageId 使用同一个 UUIDv7，跨 vhost 重投时不得重新生成。

消费者：

- 处理完成并提交必要存储后手动 ACK。
- 可重试异常进入延迟重试队列。
- 不可重试异常进入 DLQ，附错误码和原始 eventId。
- 消费次数超过阈值不再热循环。
- 每个消费者实现 eventId 或业务键幂等。

告警事件额外遵守以下已实现约束：

- Telemetry 在遥测事务中写 `telemetry_outbox_event`，提交后只按本次返回的 `eventId` 唤醒 relay；不以高频全表扫描代替可靠投递。
- relay 使用有界租约、指数退避、启动/重连有界恢复和 publisher confirm；达到尝试上限进入 `DEAD`，不得假装已经送达。
- `alarm.raised` 与 `alarm.recovered` 使用同一 `alarmOccurrenceId`，重试保持消息 `eventId` 不变，载荷携带设备、规则、策略修订/快照/哈希及有界触发或恢复证据。
- Server 使用独立只读队列账号和 `sys_inbox_event` 幂等消费；触发与恢复乱序到达时可以补齐同一周期，冲突身份或证据必须拒绝。

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

`DATA_QUALITY` 的已实现运行语义如下：

- 未来时间命中 `FUTURE_TIMESTAMP`，数值属性降级为 `TIME_UNTRUSTED`，排序时间使用 `receivedAt`。
- 陈旧时间命中 `STALE_TIMESTAMP`，数值属性降级为 `UNCERTAIN`。
- 必填属性缺失命中 `REQUIRED_MISSING` 并生成 `BAD` 证据；缺失属性本身不伪造历史样本。
- 数值低于下限或高于上限分别命中 `BELOW_MINIMUM`、`ABOVE_MAXIMUM`，对应属性降级为 `BAD`。
- 同一属性命中多条规则时取最差质量；其他正常属性继续入库，原始观测值不因质量降级而静默丢弃。

`telemetry_quality_evidence` 以 `event_id + property_id + rule_code` 为主键，保存观测值、阈值、运行修订、不可变快照标识和策略哈希。Inbox、数值历史、持久最新值、摄取水位和质量证据在同一 TimescaleDB 事务提交；事务成功后才刷新 Redis 和发布实时事件。RabbitMQ 重投会读取已提交的同一事件样本和属性质量，不因期间策略变化分叉出另一套缓存或 WebSocket 结果。

`ALARM` 的已实现运行语义如下：

- 只对已经通过持久最新值顺序状态的实时帧计算；补传、旧 boot、低序号和被拒绝覆盖的帧不推进状态机。
- 数值来源在可用质量下计算阈值与恢复迟滞；缺值、`BAD` 和 `TIME_UNTRUSTED` 返回 `UNKNOWN`，保持候选或活动状态。
- 质量来源只在本帧确实执行过对应质量规则和属性范围时判断；没有覆盖证据时同样返回 `UNKNOWN`。
- 触发持续、恢复持续、冷却和最近处理时间全部使用平台可信 `receivedAt`，接收时间倒退不推进状态。
- `telemetry_alarm_runtime_state`、`telemetry_outbox_event` 与遥测 Inbox、历史、最新值和质量证据同事务提交；事务成功后才刷新 Redis、发布实时遥测并 ACK 原消息。
- 策略激活会在事务中协调旧状态。删除、禁用或语义变化的活动规则先产生恢复 Outbox，再移除旧状态；未变化规则保留周期连续性。

告警 Outbox 发布到 `mine.business.x` 的 `alarm.raised` / `alarm.recovered`，由 quorum 队列 `mine.server.alarm-event.q` 消费。Server 以告警周期聚合触发、恢复、确认和关闭事实，恢复早到可以先建周期、触发晚到后补证据；同一事件重投不会重复创建告警或审计。

实时事件同时携带整帧最差质量和 `qualities` 逐属性质量映射。WebSocket 权限过滤属性值时必须同步过滤 `qualities`，并基于剩余可见属性重新计算整帧质量；旧事件缺少 `qualities` 时仍按兼容路径读取。

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
- ACK 必须在持久化历史和最新值读模型成功、Redis 刷新完成后；数据库失败时禁止提前修改 Redis。

## 11. Redis 数据

Redis 只保存可重建数据：

~~~text
mine:latest:{deviceId}             当前属性哈希
mine:latest-order:{deviceId}       每属性有效时间、boot、序号和事件排序游标
mine:latest-state:{deviceId}       设备当前 boot、最高序号和最近生效事件
mine:latest-retired-boots:{deviceId} 设备已退役 boot 集合
mine:presence:{deviceId}           在线会话与过期
mine:config:{nodeId}               节点当前配置版本
mine:ws:subscription:{sessionId}   短期订阅信息
~~~

规则：

- 迟到消息、同 boot 小序号和已退役 boot 不覆盖最新值。
- Redis 丢失后从新消息和 Timescale 持久最新值、boot 状态及退役 boot 集合重建。
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
- 原始数值遥测：已由 `RETENTION` 按产品、设备组或设备最终有效策略治理。
- 质量证据：独立保留天数不得短于原始数值遥测。
- UWB：近期保留原粒度，较老数据可降采样为轨迹段或分钟点。
- 原始报文：正常采样、异常短期全量。
- 告警/命令/审计：按法规和矿区要求长期保留。

当前运行消费者只物理清理 `telemetry_numeric` 和 `telemetry_quality_evidence`。告警运行状态、告警 Outbox、遥测 Inbox、持久最新值、策略镜像/修订水位、服务端告警事实和操作审计均不在首版删除边界；位置与 UWB 聚合尚未交付，不能套用本任务清理。

清理任务遵守以下不变量：

- 发布前通过只读接口读取已保存版本，按最终覆盖关系形成逐设备影响预览，返回目标/变化/仍启用设备数、统计时点、各数据集行数、最早时间、`atLeast` 上限标记和 15 分钟一次性凭证；预览是时点估算，不是上下限或删除承诺。
- 发布和回滚激活事务必须重新校验凭证绑定的管理员、版本内容、作用域、成员、组优先级和最终解析哈希；草稿未保存修改、凭证过期/复用或目标状态变化均不得激活。
- 不使用会越过单设备期限的全局 `drop_chunks`。原始遥测同时要求 hypertable 时间列和平台可信 `received_at` 超期，避免伪造设备时间导致刚入库数据被删除。
- 同一 TimescaleDB 通过会话级 advisory lock 保证单执行器；每轮按配置的 `batch-size × max-cycles` 有界推进，达到上限记为 `PARTIAL`，后续轮次继续。
- 每个删除批次与累计进度在同一事务提交。失败批次回滚后可重复扫描；超时 `RUNNING` 事实由后续实例收敛为 `FAILED`。
- `telemetry_retention_execution` 保存 `RUNNING/SUCCEEDED/PARTIAL/FAILED`、实例、策略数、两个数据集删除量、批次数和脱敏错误；终态事实禁止更新或删除。
- 禁止管理员在页面执行自由 SQL，禁止手工无条件 `DELETE`、直接修改策略镜像或删除执行事实。

`mine-server` 仅使用 TimescaleDB 只读账号提供 `POST /api/v1/retention-previews` 和 `GET /api/v1/retention-executions`。Telemetry 写账号负责策略镜像、删除和执行事实；两类职责不得合并为管理员账号。

## 14. 可观测指标

- publish confirm 延迟和失败率。
- 每队列 ready/unacked、最老消息年龄。
- 消费成功、重试、DLQ 和幂等命中。
- 解析失败、CRC 错误、未知消息。
- 迟到、乱序、sequence gap。
- 批写大小、耗时、失败、Timescale 延迟。
- Redis 写入失败和 latest 拒绝覆盖次数。
- 每个 pipeline 阶段耗时，但 deviceId 不作为 Prometheus 标签。
- 数据质量规则命中数，标签仅允许稳定 `rule` 和 `result` 枚举。
- 数据质量策略分发结果、解码/拉取/校验/暂存/激活阶段和处理耗时，不携带产品、设备或快照标识。
- 告警 Outbox 按固定状态的数量、Server 消费固定结果、确认/关闭固定结果和按固定等级聚合的活动告警数；设备、规则、告警周期和异常文本不得作为标签。
- 数据保留按固定 `status` 统计运行轮次、按固定 `dataset=raw|quality` 统计已提交删除行数，并暴露运行中、连续失败、连续 `PARTIAL`、启用策略数和最近成功时间；不得使用设备、产品、执行标识或错误文本作为标签。
