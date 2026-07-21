# 产品绑定协议路由设计

## 1. 目标与边界

本批把设备接入从“TCP 监听端口静态绑定一个插件”演进为“产品发布协议绑定，Gateway 按可信设备身份选择解析器”。MQTT JSON、MQTT Binary 和可改造 TCP 设备共享同一套产品协议绑定模型；不能改造的 TCP 设备继续通过显式兼容接入点使用现有插件，不把协议猜测作为默认能力。

首批交付不支持网页上传 JAR、在线编译脚本、热卸载插件、无界协议探测或设备级协议覆盖。Java 插件仍由运维离线审核并随不可变镜像部署。协议发布只能引用当前 Gateway 已安装且 API 主版本兼容的插件，失败候选不得覆盖 last-known-good。

## 2. 方案比较与选择

### 方案 A：所有私有协议按端口隔离

拆帧确定、风险最低，但插件增多会扩大端口和实例治理成本，也无法满足可改造设备共享统一入口，因此只保留为存量协议兼容模式。

### 方案 B：统一端口自动尝试全部插件

无需设备改造，但同一前缀可能匹配多个协议，拆帧前需要无界缓存或试错，容易产生误识别、资源放大和不稳定行为，因此不作为平台默认能力。

### 方案 C：标准入口先建立可信身份，再按产品绑定选择解析器

选用此方案。MQTT 使用 Broker 已认证身份和受控 Topic 获取产品；可改造 TCP 设备先发送平台统一登录帧，Gateway 认证后按产品绑定安装对应拆帧器。旧 TCP 协议使用显式兼容接入点，后续仅对具有唯一魔数且有严格探测上限的协议开放可选探测。

## 3. 统一产品协议绑定

产品是协议兼容边界。一个产品的当前发布绑定包含：

- `transport`：`MQTT` 或 `TCP`；
- `payloadFormat`：`JSON` 或 `BINARY`；
- `routingMode`：`TRUSTED_IDENTITY` 或 `LEGACY_LISTENER`；
- `parserType`：`CONFIGURATION` 或 `JAVA_PLUGIN`；
- `pluginKey/pluginVersion`：Java 插件解析时必填；
- `mappingVersionId`：配置化 JSON 解析时必填；
- 发布序号、内容哈希和运行节点加载状态。

首批不允许同一产品的不同设备覆盖协议。协议变更以不可变发布形成更高序号，Gateway 完整校验候选绑定、执行计划和插件能力后原子切换。现有设备身份快照继续提供 `productKey + clientId/deviceCode -> deviceId` 的可信映射，但协议版本以产品发布绑定为准，避免设备身份文件与协议发布产生两个事实源。

## 4. MQTT 数据流

MQTT 连接和消息边界由 Broker 处理，Gateway 不需要自行拆帧。消费消息时先从受控 routing key 得到 `productKey + clientId`，再从身份快照取得平台设备，随后查询产品协议绑定：

1. `MQTT + JSON + CONFIGURATION`：执行现有 `MqttJsonExecutionPlan`；
2. `MQTT + BINARY + JAVA_PLUGIN`：调用已安装的 `MqttBinaryProtocolPlugin`；
3. 绑定缺失、传输不匹配、插件不存在或版本不兼容：拒绝原消息，不进入重试风暴；
4. 内部遥测获得 RabbitMQ publisher confirm 后，才 ACK 原始 Broker 消息。

MQTT 二进制 SDK 接收完整 Payload 和可信设备上下文，不暴露 RabbitMQ、Spring 或数据库对象。插件只返回启动标识、序号、发生时间、补传标志和标准属性；产品、设备和租户身份由 Gateway 注入，不能由插件覆盖。

## 5. TCP 数据流

统一 TCP 入口使用平台固定登录帧完成路由。登录阶段有独立、固定且有界的帧规则，不调用厂商插件。登录帧至少包含魔数、协议版本、总长度、`productKey`、`deviceCode`、时间戳、随机数和认证摘要。Gateway 校验长度、时钟窗口、防重放和设备凭据后取得可信设备，再读取产品绑定。

若绑定为 `TCP + BINARY + JAVA_PLUGIN + TRUSTED_IDENTITY`，Gateway 为当前连接创建插件独占会话，并在登录处理器之后安装插件声明的帧解码器。连接一旦选定插件和设备，不允许切换产品、协议或设备身份。应用 ACK 仍只能在内部遥测 publisher confirm 成功后发送。

存量设备使用 `LEGACY_LISTENER`：监听端点显式绑定一个已审核插件，保留现有行为，但端点配置进入控制面并由前台管理，不再要求管理员手写环境变量。只有无法发送统一登录帧的设备使用该模式。

## 6. SDK 与组件边界

`mine-protocol-sdk` 新增 MQTT 二进制稳定接口：

- `MqttBinaryProtocolPlugin`：协议键、协议版本、API 主版本和解码入口；
- `MqttBinaryDecodeContext`：可信产品、设备编码和客户端标识；
- `MqttBinaryDecodedMessage`：不可变标准遥测中间结果；
- `MqttBinaryProtocolException`：固定低基数错误码。

TCP 继续使用现有 `TcpProtocolPlugin` 和 `TcpProtocolSession`。统一登录和插件选择属于 Gateway，不放进厂商 SDK。`mine-protocol-engine` 分别维护 MQTT Binary 和 TCP 插件注册表，完成 ServiceLoader、协议键唯一性和 API 版本校验。

Gateway 的产品协议注册表使用不可变快照，向 MQTT 和 TCP 提供同一查询边界。解析热路径只读内存，不查询控制面数据库。

## 7. 控制面与前端

后台提供已安装插件目录和产品协议绑定视图。插件目录来自运行节点上报的能力交集，至少展示协议键、版本、支持传输、节点覆盖和可用状态。前台不能上传 JAR，只能选择已安装插件。

“物模型与协议”页面创建协议草稿时，根据组合展示字段：

- MQTT JSON：配置字段映射和黄金样本；
- MQTT Binary：选择 MQTT Binary 插件并上传十六进制黄金样本；
- TCP Unified：选择 TCP 插件，接入地址来自统一入口；
- TCP Legacy：选择插件和兼容接入点，系统校验端口冲突。

发布前必须完成黄金样本、插件存在性、版本兼容、物模型字段和目标节点能力门禁。页面展示每个 Gateway 节点的 `LOADED/FAILED` 状态与实际哈希。

## 8. 故障、安全与测试

所有输入均有长度、数量、时间和嵌套深度上限。日志和指标不得包含原始二进制帧、凭据、设备标识或异常文本；只记录协议键、固定错误码和发布标识。统一 TCP 登录失败、插件缺失、绑定不一致、身份切换、解析失败和发布失败均关闭连接且不发送业务 ACK。

自动化覆盖 SDK 不变量、SPI 重复和版本拒绝、MQTT JSON/Binary 按产品路由、统一 TCP 登录半包粘包与认证、登录后动态 pipeline、连接内绑定不可变、Legacy 回归、配置快照原子切换、数据库中文注释、API 权限、前端条件表单、国际化和 UI 治理。真实设备、证书、容量、双节点和长稳验证保留为生产放行门禁。
