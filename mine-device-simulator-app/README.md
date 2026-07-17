# 矿山设备模拟台

这是一个独立运行的矿山设备模拟系统。后端、管理页面和静态资源打包在同一个 Spring Boot JAR 中，不依赖 `mine-server` 或 `mine-web`。

## 已实现能力

- 浏览器页面新增、编辑、删除设备配置；配置持久化为本机 YAML。
- MQTT / MQTTS 客户端，支持用户名、启动时临时密码、Topic 和 QoS 0/1/2。
- TCP Client / TCP Server，支持普通连接与基于 JVM 信任库、密钥库的 TLS。
- JSON / UTF-8 与 HEX 报文模板。
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

HEX 模板允许空格和换行，例如 `68 01 00 02 FF 16`。

## 协议边界

当前版本实现的是 MQTT 和透明 TCP 传输层，适合测试平台连接、鉴权、Topic 路由和自定义 JSON/二进制报文接入。JT/T 808、Modbus TCP、OPC UA 是应用层协议，尚未在当前版本中宣称完整实现；后续可在现有 TCP 传输层上增加编解码模板。

TLS 使用 JVM 默认信任库。TCP Server TLS 需要在启动 JVM 时提供服务端密钥库，例如：

```bash
java -Djavax.net.ssl.keyStore=/path/server.p12 \
  -Djavax.net.ssl.keyStorePassword=changeit \
  -jar mine-device-simulator-app/target/mine-device-simulator-app-0.1.0-SNAPSHOT.jar
```
