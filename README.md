# mine-platform

mine-platform 是面向单矿区私有化部署的智慧矿山数据采集与设备管理平台。平台计划支持 MQTT、厂商私有 TCP 和二进制协议，在弱网环境中完成设备接入、遥测处理、告警、命令下发和历史数据管理。

项目目前处于工程底座建设阶段，模块名为临时稳定名称，后续修改产品名称不改变模块边界。

## 工程约定

- Maven groupId：`com.bosco`
- Java 基础包：`com.bosco.mine`
- Java：21
- Spring Boot：4.1.0
- 构建方式：Maven Wrapper
- 许可证：Apache License 2.0

## 后端模块

| 模块 | 职责 |
|---|---|
| `mine-common` | 公共错误、时间、标识等基础能力 |
| `mine-device-model` | 产品、物模型和统一设备消息 |
| `mine-messaging` | 内部消息契约与 RabbitMQ 拓扑 |
| `mine-protocol-sdk` | Java 协议插件开发接口 |
| `mine-protocol-engine` | 可配置协议解析引擎 |
| `mine-gateway` | Netty、MQTT 设备接入和命令下发 |
| `mine-telemetry` | 遥测处理、告警计算和时序入库 |
| `mine-server` | 矿山业务、系统管理和管理 API |

## 开发命令

~~~bash
./mvnw test
~~~

完整设计、架构决策和分阶段实施计划见 [docs/README.md](docs/README.md)。

单机一体化环境的构建、配置、启动和验收步骤见 [deploy/README.md](deploy/README.md)。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。第三方软件仍遵循其各自许可证，发行时必须保留对应声明。
