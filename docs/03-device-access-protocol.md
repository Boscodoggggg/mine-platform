# 03 设备接入、协议引擎与弱网设计

## 1. 接入总体设计

设备来源分为三类：

1. 标准 MQTT 设备：直接连接平台部署的 RabbitMQ MQTT 监听器。
2. 私有 TCP 设备：连接 mine-gateway 的 Netty 监听器。
3. 可控车载终端：可选部署 mine-edge，先本地缓存再 MQTT 上传。

mine-edge 不是每台矿车必须安装的中心代理。如果厂商终端已具备固定 clientId、序号、时间戳和断网缓存，可直接接入 Broker。

## 2. RabbitMQ MQTT 入口

平台使用一个 RabbitMQ 集群、两个 vhost：

| vhost | 用途 | 客户端 |
|---|---|---|
| /mine-device | 设备 MQTT、原始上报和设备下行 | 设备、mine-edge、mine-gateway |
| /mine-platform | 平台内部 AMQP 事件 | gateway、telemetry、server |

逻辑隔离要求：

- vhost 使用不同用户、权限、exchange、queue 和连接限额。
- 设备账号无权访问 /mine-platform。
- 内部服务账号无权使用设备通配主题向任意设备发送。
- 单集群仍共享内存、磁盘告警和故障域，必须通过压测、TTL、队列上限和资源告警控制。
- MQTT 监听端口通过 RabbitMQ mqtt_port_to_vhost_mapping 固定映射到 /mine-device，避免让设备自行选择内部 vhost。

## 3. MQTT 主题

~~~text
mine/v1/{productKey}/{deviceId}/up/telemetry
mine/v1/{productKey}/{deviceId}/up/event
mine/v1/{productKey}/{deviceId}/up/heartbeat
mine/v1/{productKey}/{deviceId}/up/command-ack
mine/v1/{productKey}/{deviceId}/down/command
~~~

约束：

- 主题层级保持有限，deviceId/productKey 只能使用安全字符。
- 禁止设备在 topic 中插入点号造成 MQTT 到 AMQP 路由歧义。
- 每个设备只能发布自己的 up 主题、订阅自己的 down 主题。
- 默认 MQTTS 8883，1883 仅允许受控内网兼容场景。
- 禁止匿名连接。

QoS 建议：

- 遥测和 UWB：QoS 0/1 由丢失容忍度和压测决定。
- 告警、事件、命令、命令回复：QoS 1。
- Broker PUBACK 不等于设备执行成功。

## 4. Netty Gateway

TCP pipeline 采用：

~~~text
连接限流
→ 空闲检测
→ TLS（可选/推荐）
→ 报文边界解码
→ 登录/设备识别
→ 协议执行计划
→ 统一消息转换
→ RabbitMQ发布确认
~~~

每个连接保存最小会话状态：

- channelId、远端地址、连接时间。
- deviceId/productKey、protocolVersion。
- 登录状态、最后读写时间、sequence 状态。
- 当前配置快照版本。

会话状态不存放大量历史报文。原始报文按采样/异常策略异步写对象存储。

## 5. 配置协议与 Java 插件边界

### 5.1 前台可配置

- 固定长度、长度字段、分隔符和 TLV 拆帧。
- JSON、键值、固定偏移二进制。
- byte/short/int/long/float/double、BCD、ASCII、位域。
- 大小端、有符号/无符号、比例、偏移、单位。
- 枚举、布尔、时间、经纬度或局部坐标映射。
- CRC、校验和、简单转义。
- 登录、心跳、ACK 和普通命令编码。

### 5.2 Java 插件

- 动态密钥和厂商私有加解密。
- 多步骤会话状态机。
- 一条报文依赖多条历史上下文。
- 文件分片、固件升级、复杂压缩。
- 规则引擎无法安全、确定性表达的逻辑。

前台不得上传任意 class 直接执行。插件 JAR 由运维离线审核、签名、安装和重启/热部署流程管理，具体取决于安全测试结果。

## 6. 协议定义模型

~~~text
ProtocolDefinition
├── metadata
│   ├── protocolKey
│   ├── transport: MQTT | TCP
│   └── description
├── framing
│   ├── mode: FIXED | LENGTH_FIELD | DELIMITER | TLV
│   ├── maxFrameLength
│   └── escape/checksum
├── messages[]
│   ├── messageType
│   ├── match
│   ├── fields[]
│   ├── validation[]
│   └── ack
└── commandEncoders[]
~~~

字段至少描述：

- name、targetProperty、dataType。
- offset/length 或 path。
- byteOrder、signed、bitOffset/bitLength。
- scale、offsetValue、unit。
- enumMapping、nullValue、validRange。
- required、qualityOnFailure。

## 7. 编译执行模型

发布时：

1. JSON Schema 校验结构。
2. 语义校验偏移、长度、类型和目标物模型。
3. 检查字段重叠、越界、循环引用和无界长度。
4. 运行协议样本集。
5. 编译为不可变 ProtocolExecutionPlan。
6. 计算内容哈希并进入配置包。

运行时：

- 按 protocolVersion 获取已经编译的执行计划。
- 不在每条消息上查询数据库。
- 不在每条消息上解析协议配置 JSON。
- 快照替换使用原子引用；已有 TCP 会话是否切换由兼容策略决定。
- 解析失败输出结构化错误，不抛出未捕获异常关闭整个消费线程。

## 8. 协议工作台

前端至少包含：

- 基本信息与传输方式。
- 帧结构设计器。
- 消息类型列表。
- 字段表格和位域编辑器。
- 十六进制/文本样本输入。
- 分段着色结果。
- CRC/长度/匹配过程。
- 解析后的统一消息。
- 旧版本对比和全部样本回归。

每个发布版本必须有：

- 正常上报样本。
- CRC/校验错误样本。
- 半包和粘包样本。
- 边界值和未知消息样本。
- 至少一个命令编码及回复样本（如果协议支持下行）。

## 9. 统一设备消息

gateway 输出的消息不暴露厂商原始字段名：

~~~json
{
  "messageId": "uuid-v7",
  "messageType": "TELEMETRY",
  "productKey": "haul-truck",
  "deviceId": "device-001",
  "protocolVersion": "truck-vendor-a@3",
  "bootId": "boot-20260714-01",
  "sequence": 12345,
  "occurredAt": "2026-07-14T08:00:00Z",
  "receivedAt": "2026-07-14T08:00:02Z",
  "replayed": false,
  "properties": {
    "engine.rpm": 1350,
    "coolant.temperature": 82.5
  },
  "quality": "GOOD",
  "rawRef": "s3://raw/..."
}
~~~

`replayed` 是必填字段，禁止把缺失值静默当作 `false`，否则弱网补传可能错误覆盖最新值。`properties` 只接受 JSON 的 null、标量、对象和数组；进入统一消息后必须递归复制并冻结，保证同一 messageId 的内容不会在异步投递期间变化。

## 10. 弱网与补传

### 10.1 设备能力分级

| 级别 | 能力 |
|---|---|
| 基础 | 只有 deviceId 和服务器接收时间 |
| 标准 | 具有 bootId、sequence、occurredAt |
| 可靠 | 标准能力 + 本地持久缓存 + 应用级 ACK |

平台在产品/设备页面显示能力等级，不能把基础设备伪装成可保证完整补传。

### 10.2 幂等与乱序

推荐幂等键：

~~~text
(deviceId, bootId, sequence, messageType)
~~~

处理规则：

- 相同幂等键只产生一次历史写入和业务事件。
- occurredAt 早于当前值时间时，可以写历史，但不得覆盖 Redis 最新值。
- sequence 出现缺口时记录 gap 指标和设备事件。
- bootId 变化表示设备重启，sequence 可重新开始。
- 没有 sequence 的设备使用 messageId/载荷哈希和短时间窗做有限去重，并明确可靠性较低。

### 10.3 缓存边界

mine-edge 或设备本地缓存必须设置容量和保留期。空间不足时按数据等级处理：

1. 告警、命令回复和作业事件优先保留。
2. 普通遥测按时间降采样。
3. 高频位置点允许按配置丢弃最旧低质量数据。
4. 任何丢弃都产生本地计数，恢复连接后上报数据缺口事件。

## 11. 命令状态机

~~~mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> QUEUED
    QUEUED --> SENT
    SENT --> DEVICE_RECEIVED
    DEVICE_RECEIVED --> SUCCEEDED
    DEVICE_RECEIVED --> REFUSED
    DEVICE_RECEIVED --> FAILED
    QUEUED --> EXPIRED
    SENT --> TIMEOUT
    DEVICE_RECEIVED --> TIMEOUT
    CREATED --> CANCELLED
~~~

每个命令包含：

- commandId、deviceId、templateVersion。
- 参数、创建人、原因、危险等级。
- ttl、timeout、retryPolicy、idempotencyKey。
- 编码时使用的 protocolVersion/configReleaseId。

危险命令默认不允许设备离线排队，不自动重试；页面必须二次确认并记录原因。

## 12. 原始报文

- 正常遥测按低比例采样，异常解析可全量短期保留。
- 原始内容存对象存储，业务库只存 rawRef、哈希、大小、时间和脱敏摘要。
- 涉及凭据、个人信息或厂商敏感内容时先脱敏/加密。
- 保留期由策略控制，过期任务必须可观测、可重试、可审计。
