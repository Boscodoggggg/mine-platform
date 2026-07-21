# 矿山设备模拟台

这是一个独立运行的矿山设备模拟系统。后端、管理页面和静态资源打包在同一个 Spring Boot JAR 中，不依赖 `mine-server` 或 `mine-web`。

## 已实现能力

- 浏览器页面新增、编辑、删除设备配置；配置持久化为本机 YAML。
- MQTT / MQTTS 客户端，支持用户名、启动时临时密码、Topic 和 QoS 0/1/2。
- TCP Client / TCP Server，支持普通连接与基于 JVM 信任库、密钥库的 TLS。
- JSON / UTF-8 与 HEX 报文模板。
- 固定种子的圆形/折线定位路线，以及低质量、重复、乱序、未绑定标签和越界异常场景。
- 周期发送、单次发送、断开、重连、停止。
- 连接状态、成功/失败计数及每台设备最近 200 条运行日志。
- 密码不写入 YAML，也不写入浏览器存储。

## 启动

在仓库根目录执行：

```bash
./mvnw -pl mine-device-simulator-app -am package
java -jar mine-device-simulator-app/target/mine-device-simulator-app-0.1.0-SNAPSHOT.jar
```

浏览器打开：<http://127.0.0.1:18090>

### Docker 启动

先打包 JAR，再构建并启动容器：

```bash
./mvnw -pl mine-device-simulator-app -am package
docker compose -f mine-device-simulator-app/compose.yml up -d --build
```

Compose 仅将管理端口发布到宿主机 `127.0.0.1:18090`。容器内使用非回环监听只是为了完成 Docker 端口转发，不会直接向局域网发布管理页面。

默认配置文件：

```text
~/.mine-device-simulator/profiles.yaml
```

可用环境变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `MINE_SIMULATOR_BIND_ADDRESS` | `127.0.0.1` | 管理页面回环监听地址，仅允许 `127.x.x.x`、`localhost` 或 `::1` |
| `MINE_SIMULATOR_PORT` | `18090` | 管理页面端口 |
| `MINE_SIMULATOR_PROFILE_FILE` | `~/.mine-device-simulator/profiles.yaml` | YAML 配置路径 |
| `MINE_SIMULATOR_ALLOW_NON_LOOPBACK` | `false` | 仅供已限制宿主机端口的容器部署显式启用 |

例如使用临时配置文件启动：

```bash
MINE_SIMULATOR_PROFILE_FILE=/tmp/mine-simulator.yaml \
java -jar mine-device-simulator-app/target/mine-device-simulator-app-0.1.0-SNAPSHOT.jar
```

## 连接方式

| 模式 | 模拟器角色 | 平台侧准备 |
| --- | --- | --- |
| MQTT | 客户端 | 启动 Broker，提供地址、端口、账号与上行 Topic |
| TCP Client | 客户端 | 平台先监听 TCP 端口，模拟器主动连接 |
| TCP Server | 服务端 | 模拟器先监听本机端口，平台主动连接 |

TCP Server 配置中的主机字段是设备协议监听地址，它与管理页面监听地址相互独立。一般使用 `127.0.0.1`；需要局域网平台连接模拟设备时，可在设备配置中填写本机网卡地址或 `0.0.0.0`，同时检查系统防火墙。管理页面自身始终只允许绑定回环地址。

## 报文变量

JSON 和 HEX 模板发送前会替换以下变量：

| 变量 | 含义 |
| --- | --- |
| `${sequence}` | 当前任务内从 1 递增的发送序号 |
| `${timestamp}` | ISO-8601 UTC 时间 |
| `${epochMillis}` | Unix 毫秒时间戳 |
| `${productKey}` | 产品标识 |
| `${deviceCode}` | 设备编码 |
| `${clientId}` | MQTT Client ID |
| `${locationTagCode}` | 定位场景生成的标签编码 |
| `${locationBootId}` | 根据模拟器配置生成的稳定启动周期前缀 |
| `${locationSequence}` | 场景生成的来源序号，重复/乱序场景可能回退 |
| `${locationOccurredAt}` | 与来源序号一致的 ISO-8601 设备发生时间 |
| `${locationX}` / `${locationY}` / `${locationZ}` | 固定路线生成的来源坐标 |
| `${locationSpeed}` / `${locationHeading}` | 由相邻点和发送周期计算的速度与航向 |
| `${locationAccuracy}` | 场景生成的精度半径（米） |
| `${locationScenario:CIRCLE}` | 选择定位场景并输出稳定场景名 |

HEX 模板允许空格和换行，例如 `68 01 00 02 FF 16`。

定位变量只允许用于 JSON 模板。在设备向导的报文步骤选择“定位模拟场景”并应用模板，模拟器会生成平台固定
JSON v1 报文；Topic 必须使用 `mine/v1/{productKey}/{clientId}/up/location`。模板默认来源坐标系编码是
`uwb-local`，运行前必须改成控制面已发布快照中的实际来源坐标系编码。

| 场景 | 行为 |
|---|---|
| `CIRCLE` | 可重复的圆形路线 |
| `POLYLINE` | 可重复的矿区道路折线 |
| `LOW_QUALITY` | 持续输出较差精度，用于验证降级历史与当前位置保护 |
| `DUPLICATE` | 每 5 帧重复上一帧的序号、时间和坐标 |
| `OUT_OF_ORDER` | 每 5 帧回退两个序号与发生时间 |
| `UNBOUND_TAG` | 使用 `SIM-UNBOUND-718`；需先在矿区建档但不要绑定车辆或人员 |
| `OUT_OF_BOUNDS` | 输出明显越过矿区边界的坐标，用于验证有界隔离 |

完整准备、预期、指标和回退方法见
[矿区定位运行与模拟验收手册](../docs/runbooks/mine-location-runtime.md)。模拟器是开发和自动化验收工具，不替代真实
UWB 厂商协议、坐标标定、精度、容量、高可用和长稳验证。

## 三维场景联调演示

仓库提供可离线、可复现的自制三维资源
[`demo-mine-grid.glb`](../deploy/demo/scene/demo-mine-grid.glb)。先按
[`确定性矿区三维演示资源`](../deploy/demo/scene/README.md)上传、发布并设为矿区默认场景，再用 `CIRCLE` 或
`POLYLINE` 定位场景持续发送数据，即可验证以下自动化和演示链路：

- 来源坐标经控制面已发布的双坐标模型转换为矿区局部 ENU 坐标；
- 同一位置增量同时进入三维视口和完全独立的二维画布；
- MQTT、MQTTS 或 TCP 透明传输保持既有协议边界；
- 静态 GLB、WebGL 或实时业务图层故障时按图层独立降级。

演示前必须把模板中的 `sourceCoordinateSystemCode` 改为目标矿区已发布坐标快照中的真实编码。该流程只验证平台数据闭环，
不构成真实设备协议、矿区测绘、坐标标定、GPU 兼容性或现场生产资格证明。

## 协议边界

模拟器实现 MQTT 和透明 TCP 传输层，适合测试平台连接、鉴权、Topic 路由和自定义 JSON/二进制报文接入。平台已经提供
`haul-truck-binary` 非厂商 TCP 二进制纵向示例，可按
[黄金帧与字段说明](../examples/protocols/tcp-binary/haul-truck/README.md)配置 TCP Client + HEX 单次发送。JT/T 808、
Modbus TCP、OPC UA 仍未宣称实现，不能把透明传输或仓库示例当作对应行业协议。

TLS 使用 JVM 默认信任库。TCP Server TLS 需要在启动 JVM 时提供服务端密钥库，例如：

```bash
java -Djavax.net.ssl.keyStore=/path/server.p12 \
  -Djavax.net.ssl.keyStorePassword=changeit \
  -jar mine-device-simulator-app/target/mine-device-simulator-app-0.1.0-SNAPSHOT.jar
```
