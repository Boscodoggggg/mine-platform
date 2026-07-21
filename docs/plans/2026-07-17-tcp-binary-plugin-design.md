# TCP 二进制协议插件纵向样例设计

## 1. 目标与边界

本切片交付可上线产品需要的第一条私有 TCP 二进制纵向链路：稳定 Java SPI、受控 Netty 监听器、可信设备身份绑定、可靠遥测发布、Broker 确认后应用 ACK，以及一个可复现的矿卡示例插件。它必须用自动化证明 CRC 错误、半包、粘包、超长帧、连接空闲、身份切换和发布失败都不会产生错误确认。

首版不提供网页上传 JAR、运行任意脚本、动态密钥、在线热卸载或厂商协议兼容声明。插件由运维离线审核并在镜像构建时加入 classpath，监听端口显式绑定一个 `protocolKey`。真实设备、真实证书、矿区网络、容量和长稳仍保持延期。

## 2. 分层架构

`mine-protocol-sdk` 只依赖稳定 Java 类型，提供 `TcpProtocolPlugin`、每连接 `TcpProtocolSession`、有界 `TcpFrameDefinition`、不可变 `TcpDecodedMessage` 和固定错误码异常。SPI 不暴露 Netty、Spring、RabbitMQ 或平台数据库类型，第三方插件不能取得容器和文件系统能力。

`mine-protocol-engine` 提供 ServiceLoader 注册、协议键唯一性和 API 版本兼容检查。Gateway 把帧定义转换为 Netty pipeline，负责连接上限、空闲关闭、可选 TLS、拆帧、会话身份绑定、统一消息转换、低基数指标和生命周期。只有内部 RabbitMQ 发布获得 publisher confirm 后，Gateway 才把插件生成的 ACK 写回设备；发布失败时关闭连接，让设备按自身序号重传。

示例插件放在独立 Maven 模块，通过标准 `META-INF/services` 注册。Gateway 只编译依赖 SDK，示例插件以运行时依赖进入演示镜像，后续厂商插件可替换而不修改 Gateway 核心。

## 3. 示例帧契约

示例协议使用大端字节序和总帧长度字段：`magic(2) + version(1) + type(1) + totalLength(2) + deviceCodeLength(1) + deviceCode + bootIdLength(1) + bootId + sequence(4) + occurredAtEpochSeconds(8) + flags(1) + rpm(2) + speedDeciKph(2) + payloadDeciTon(2) + crc16(2)`。设备编码和启动标识只允许 1～32 个 ASCII 安全字符；总帧不得超过 256 字节。CRC 使用 CRC16-CCITT-FALSE，覆盖 CRC 字段之前的全部字节。

首版只接受遥测类型 `0x01`。属性映射为 `engine.rpm`、`vehicle.speed` 和 `vehicle.payload`；后两者按十分之一缩放为十进制。ACK 类型为 `0x81`，回显原序号和固定结果码，并同样包含长度与 CRC。设备编码来自帧但不被直接信任，Gateway 必须使用当前完整身份快照按 `productKey + deviceCode` 反查平台设备；同一 TCP 连接首个有效帧绑定身份，后续帧切换设备立即关闭。

## 4. 故障、安全与可观测性

Netty 在插件解码前完成最大长度限制，半包留在累积缓冲区，粘包逐帧下发。CRC、魔数、版本、类型、长度、身份和字段范围失败使用固定错误码计数并关闭连接，不把原始字节、设备凭据或异常堆栈写入普通日志。每个远端地址和全局连接数都有上限，读空闲会主动释放会话。

TLS 在应用默认关闭，生产必须通过证书链与私钥文件显式启用；明文只允许受控内网演示。指标只包含协议键、结果和固定原因，不包含设备、IP、帧内容或异常文本。核心告警覆盖监听器不可用、拒绝连接、连续解析失败、发布失败和长时间无成功遥测。

## 5. 验收

自动化至少覆盖 SDK 不变量、SPI 重复/版本拒绝、示例黄金帧与 ACK、CRC/截断/范围错误、EmbeddedChannel 半包粘包、可信身份绑定、连接内身份切换、publisher confirm 顺序、发布失败无 ACK、回环 TCP 生命周期、Compose 参数和 Prometheus 规则。文档必须给出示例 HEX、模拟器配置、插件打包边界、升级与回退步骤，并明确非现场验证不等于生产放行。
