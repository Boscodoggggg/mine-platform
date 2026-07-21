# 03 设备接入、协议引擎与弱网设计

## 1. 接入总体设计

设备来源分为三类：

1. 标准 MQTT 设备：直接连接平台部署的 RabbitMQ MQTT 监听器。
2. 私有 TCP 设备：连接 mine-gateway 的 Netty 监听器。
3. 可控车载终端：可选部署 mine-edge，先本地缓存再 MQTT 上传。

mine-edge 不是每台矿车必须安装的中心代理。如果厂商终端已具备固定 clientId、序号、时间戳和断网缓存，可直接接入 Broker。

接入端口按传输协议、安全边界和遗留兼容性划分，不按插件数量划分：

| 接入类型 | 入口 | 产品绑定后的解析方式 |
|---|---|---|
| MQTT JSON | 共享 MQTTS 端口 | 可信 topic/clientId 定位产品，执行产品绑定的配置协议 |
| MQTT 二进制 | 共享 MQTTS 端口 | 可信 topic/clientId 定位产品，执行产品绑定的 Java 插件 |
| 可改造 TCP 二进制 | 共享统一 TLS 端口 | 固定登录帧认证产品和设备，再执行产品绑定的 Java 插件 |
| 不可改造 TCP 二进制 | 显式遗留监听器 | 监听器绑定插件，插件报文身份再与产品和设备快照核验 |

因此十个插件不代表必须开放十个端口。只有无法发送统一登录帧、无法经 MQTT 携带可信产品身份，且协议之间无法可靠判别的旧设备，才需要独立遗留监听器。边缘网关只用于现场断网缓存、串口/总线汇聚或网区隔离，不是产品协议路由的必需组件。

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
mine/v1/{productKey}/{clientId}/up/telemetry
mine/v1/{productKey}/{clientId}/up/event
mine/v1/{productKey}/{clientId}/up/heartbeat
mine/v1/{productKey}/{clientId}/up/command-ack
mine/v1/{productKey}/{clientId}/down/command
~~~

约束：

- 主题层级保持有限，clientId/productKey 只能使用安全字符。
- clientId 使用平台签发凭据中的 username；业务 deviceCode 放在报文内，由平台按凭据映射并校验，二者不得混用。
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
→ TLS（可选；生产必需）
→ 读空闲检测
→ 有界报文边界解码
→ 每连接插件会话
→ 可信设备身份绑定
→ 统一消息转换
→ RabbitMQ发布确认
→ 应用级ACK
~~~

`UNIFIED` 模式在有界报文解码后先处理平台固定的 `MNTL` 登录帧，使用身份快照中的 BCrypt 摘要认证设备，随后按产品发布快照选择插件并替换业务帧解码器。`LEGACY` 模式保留监听器静态绑定插件，用于不能改造的存量设备。统一模式强制 TLS；登录密钥明文不得写入快照、日志或配置包。

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

Phase 2 已交付 MQTT/TCP 二进制插件 ABI。`mine-protocol-sdk` 只向插件传递不可变 Java 类型、固定错误码、帧定义和每连接会话，
不注入 Spring、Netty、RabbitMQ 或平台数据库对象；`mine-protocol-engine` 使用标准 ServiceLoader 原子加载，协议键重复、格式非法或
API 主版本不兼容会阻止监听器启动。插件 JAR 仍是与 Gateway 同进程运行的受信代码，不构成安全沙箱，因此必须在构建镜像前完成
源码/字节码、依赖、文件与网络访问审计；当前不支持网页上传、任意脚本或热卸载。

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

### 7.1 Phase 2 已实现的 MQTT JSON 执行边界

Phase 2 首批实现限定为 MQTT JSON。管理服务和 Gateway 共同依赖 `mine-protocol-engine`，发布门禁与现场解析不会各自维护一套规则。当前支持：

- 点路径读取扁平或嵌套字段；元数据路径与属性路径分开定义。
- `INTEGER`、`DECIMAL`、`BOOLEAN`、`STRING`、`ENUM`、`DATETIME` 等白名单类型。
- `NONE`、`SCALE`、`OFFSET`、`SCALE_OFFSET`、`ENUM` 白名单转换；发布链路拒绝任意 `EXPRESSION`。
- 可信 MQTT 凭据身份与报文中的 productKey、deviceCode 双向校验。
- 字段数量、路径深度、样本数量、单样本和单快照大小上限。
- 结构化错误码；任何候选配置的哈希、结构或编译失败都不能覆盖 last-known-good。

同一产品在一个节点上同一时刻只激活一个协议执行计划。因此产品是当前协议兼容边界：若同型号设备的厂商报文结构互不兼容，应拆分为不同产品，或等待后续设备组/设备级作用域，不得在一个产品快照里加入不可审计的设备特判。

配置发布和运行时切换顺序如下：

~~~mermaid
sequenceDiagram
    participant UI as 协议工作台
    participant Server as mine-server
    participant DB as PostgreSQL / Outbox
    participant MQ as RabbitMQ config
    participant Gateway as mine-gateway
    participant Cache as 本地 last-known-good

    UI->>Server: 保存并回放黄金样本
    Server->>Server: 使用同源协议引擎编译和执行
    UI->>Server: POST config-releases + Idempotency-Key
    Server->>DB: 原子写入配置包、发布事实和 Outbox
    DB-->>MQ: config.release.created
    MQ-->>Gateway: releaseId/packageId/hash/sequence
    Gateway->>Server: 服务令牌拉取完整非敏感快照
    Gateway->>Gateway: 校验哈希、编译、按序号 CAS 激活
    Gateway->>Cache: 同目录临时文件原子替换
    Gateway->>Server: 回报 LOADED 和实际 hash
    Gateway-->>MQ: 缓存与状态回报成功后 ACK
~~~

### 7.2 Phase 2 已实现的 MQTT/TCP 二进制插件边界

产品协议草稿显式保存 `parserType`、`pluginKey`、`pluginVersion` 和 `routingMode`。发布时管理服务必须在本节点发现相同键和版本的插件，并用二进制黄金样本执行解析；运行时 Gateway 只接受哈希正确、版本递增且绑定完整的产品快照。MQTT JSON 使用配置定义，MQTT 二进制和 TCP 二进制使用插件，二者都不能由报文内容任意指定插件。

MQTT 二进制黄金样本使用 JSON 信封承载原始字节和可选连接上下文：

~~~json
{"payloadHex":"01020304","clientId":"device-001","deviceCode":"truck-001"}
~~~

首条 TCP 纵向链路使用 `haul-truck-binary` 示例插件证明以下运行语义：

- `UNIFIED` 模式一个 TLS 监听端口服务多个产品，固定登录帧认证后按产品选择插件；`LEGACY` 模式显式绑定一个受控 `protocolKey`。
- SDK 只允许 1、2、4 字节长度字段和 32 字节～1 MiB 有界帧；示例协议进一步限制为 256 字节。
- Netty 在插件解码前处理半包、粘包和超长帧；CRC、魔数、版本、类型、长度、身份和字段范围失败均关闭当前连接。
- 每个连接创建独立插件会话，首个有效帧绑定平台设备；后续帧切换设备身份立即拒绝。
- 帧内只提供 `productKey + deviceCode` 候选身份，Gateway 必须从完整身份快照反查平台 `deviceId`，并校验
  `protocolVersion=haul-truck-binary:v1`，不能信任设备自报的平台标识。
- 协议解析和同步 publisher confirm 在专用有序业务线程执行，不阻塞 Netty IO 线程。
- 只有 RabbitMQ publisher confirm 成功后才生成并写出应用 ACK；解析、身份、发布或 ACK 编码失败均不得确认。
- TLS、全局/单来源连接上限、读空闲、优雅停止、健康状态和低基数指标由 Gateway 统一治理，插件不能绕过。

固定登录帧使用网络字节序：`MNTL` 魔数、1 字节版本、2 字节总长，再依次放置 2 字节长度前缀的 `productKey`、`deviceCode`、`username`、`secret`，整帧最大 512 字节。认证成功只返回固定 6 字节确认，然后连接才进入产品插件的业务帧 pipeline。登录失败统一关闭连接，不回显 secret 或可用于枚举账号的细节。

黄金帧、字段表和模拟器步骤见
[矿卡 TCP 二进制示例](../examples/protocols/tcp-binary/haul-truck/README.md)，部署、告警、升级与回退见
[TCP 二进制 Gateway 运行手册](runbooks/tcp-binary-gateway.md)。该示例不对应任何真实厂商协议，也不代表 JT/T 808、
Modbus TCP 或 MQTT Binary 已实现。

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

当前产品协议页把路由选择作为产品配置的一部分：MQTT JSON 选择配置解析并维护字段映射；MQTT 二进制选择插件键和版本；TCP 二进制选择插件键、版本以及“统一入口”或“遗留监听器”。插件型协议使用 `payloadHex` 黄金样本信封，不显示无意义的字段映射操作。前台只保存选择和测试数据，不上传、编译或执行任意代码。

每个发布版本必须有：

- 正常上报样本。
- CRC/校验错误样本。
- 半包和粘包样本。
- 边界值和未知消息样本。
- 至少一个命令编码及回复样本（如果协议支持下行）。

Phase 2 工作台已经交付 MQTT JSON 样本的列表、编辑、全量保存、逐条回放结果和发布门禁。仓库在 `examples/protocols/mqtt-json` 提供三类可执行基线：

| 示例 | 覆盖能力 | 统一结果 |
|---|---|---|
| `flat` | 扁平字段和直接映射 | 发动机转速、车速 |
| `nested` | 嵌套元数据和嵌套属性 | 发动机转速、冷却液温度 |
| `enum-scale` | 数值缩放、枚举码表、未知码拒绝 | 电机电流、运行状态 |

TCP 二进制示例现已覆盖长度字段、CRC、半包、粘包、可信身份和成功 ACK；MQTT Binary、厂商命令编码、动态密钥、
复杂加解密和多步骤业务会话仍属于后续适配器，不能用本示例替代真实厂商协议验收。

## 9. 统一设备消息

Phase 1 的示例 MQTT JSON 报文在进入 gateway 前使用以下最小结构：

~~~json
{
  "productKey": "haul-truck",
  "deviceCode": "simulator-001",
  "bootId": "simulator-boot-001",
  "sequence": 4,
  "occurredAt": "2026-07-15T00:00:03Z",
  "replayed": true,
  "properties": {
    "engine.rpm": 1280,
    "coolant.temperature": 82.5
  }
}
~~~

该结构仅是首个适配器输入契约，不等于所有厂商必须修改报文。后续协议引擎负责把差异化 JSON、TCP 和二进制报文转换为统一消息。

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
