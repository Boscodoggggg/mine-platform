# 矿卡 TCP 二进制示例协议

本目录描述 `mine-protocol-example-haul-truck` 的可复现输入。它只用于验证 mine-platform 的 TCP 插件 SPI、拆帧、CRC、可信身份、可靠发布和应用 ACK，不对应任何真实厂商、JT/T 808、Modbus 或其他行业协议。

## 帧格式

所有多字节整数均为大端。长度字段表示包含 CRC 在内的完整帧长度，最大 256 字节。CRC 使用 CRC16-CCITT-FALSE：初始值 `0xffff`、多项式 `0x1021`、不反射、无最终异或。

| 顺序 | 字段 | 长度 | 说明 |
|---:|---|---:|---|
| 1 | magic | 2 | 固定 `0x4d50` |
| 2 | version | 1 | 固定 `0x01` |
| 3 | type | 1 | 遥测 `0x01` |
| 4 | totalLength | 2 | 完整帧总字节数 |
| 5 | deviceCodeLength + deviceCode | 1 + N | 1～32 字节 ASCII 安全字符 |
| 6 | bootIdLength + bootId | 1 + N | 1～32 字节 ASCII 安全字符 |
| 7 | sequence | 4 | 无符号启动周期序号 |
| 8 | occurredAt | 8 | UTC Unix 秒 |
| 9 | flags | 1 | bit0 为补传标志，其他位必须为 0 |
| 10 | engineRpm | 2 | 0～10000 rpm |
| 11 | speedDeciKph | 2 | 十分之一 km/h，最大 250.0 km/h |
| 12 | payloadDeciTon | 2 | 十分之一吨，最大 200.0 t |
| 13 | crc16 | 2 | 覆盖此前全部字节 |

黄金帧：

~~~text
4d 50 01 01 00 33 09 74 72 75 63 6b 2d 30 30 31
0d 62 6f 6f 74 2d 32 30 32 36 30 37 31 37 00 00
00 2a 00 00 00 00 6a 59 7f 0b 00 05 46 00 7d 01
c8 25 4a
~~~

它表示设备 `truck-001`、启动标识 `boot-20260717`、序号 42、转速 1350 rpm、车速 12.5 km/h、载重 45.6 t、非补传。标准属性输出为 `engine.rpm`、`vehicle.speed` 和 `vehicle.payload`。

成功 ACK 固定为 13 字节：`magic + version + 0x81 + totalLength + sequence + 0x00 + crc16`。Gateway 只有在内部 RabbitMQ publisher confirm 成功后才允许发送 ACK；CRC、身份、解析或发布失败均不得确认。

## 构建与模拟

~~~bash
./mvnw -pl examples/protocol-plugins/haul-truck -am test
./mvnw -pl examples/protocol-plugins/haul-truck -am package
~~~

在设备模拟器中选择“TCP Client”和 `HEX` 报文，把黄金帧复制为发送内容，目标端口使用 Gateway 为 `haul-truck-binary` 显式配置的监听端口。设备身份快照必须存在 `productKey=haul-truck`、`deviceCode=truck-001` 的唯一映射。

示例插件通过 `META-INF/services/com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin` 注册。生产厂商插件必须离线审核、使用自己的协议键和测试资产，并在镜像构建时加入 classpath；当前版本不支持从管理页面上传或执行任意 JAR。
