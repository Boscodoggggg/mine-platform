# mine-platform

mine-platform 是面向单矿区私有化部署的智慧矿山数据采集与设备管理平台。平台计划支持 MQTT、厂商私有 TCP 和二进制协议，在弱网环境中完成设备接入、遥测处理、告警、命令下发和历史数据管理。

项目目前处于工程底座建设阶段，模块名为临时稳定名称，后续修改产品名称不改变模块边界。

告警通知控制面已经提供“告警管理 → 通知渠道”统一入口，可维护平台内置 Provider、加密凭据、接收组、接收对象、受控模板和不可变升级策略。当前结论仅覆盖仓库自动化与本地管理员闭环，不包含真实短信、电话、邮件、企业 IM 送达、异步回执或事件单运行时；上线边界和验收证据见 [通知 Provider 配置与安全运行手册](docs/runbooks/notification-providers.md) 与 [Phase 2 通知控制面验收报告](docs/reports/phase-2-notification-control-plane-acceptance.md)。

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
| [`mine-device-simulator-app`](mine-device-simulator-app/README.md) | 独立单 JAR 矿山设备模拟台，提供 MQTT、TCP 和内置管理页面 |
| `examples/device-simulator` | 可重复 MQTT 遥测、弱网与命令回复模拟器 |

## 开发命令

~~~bash
./mvnw test
~~~

提交前执行全仓质量门禁：

~~~bash
bash scripts/verify.sh
~~~

该命令会验证后端测试与架构规则、前端类型/规范/测试/构建、Compose 和可观测性配置，并在 `target/sbom/` 生成后端与前端 CycloneDX 1.6 SBOM。AGPL、SSPL 和 Commons Clause 依赖默认禁止自动引入，需要架构与法务评审后才能调整门禁。

GitHub Actions 会并行执行同样的后端、前端、部署配置和 SBOM 检查；失败时保留测试报告。Dependabot 每周检查 Maven、pnpm、Compose 镜像与 Actions 依赖，但版本升级仍需通过完整质量门禁和现场回归。

完整设计、架构决策和分阶段实施计划见 [docs/README.md](docs/README.md)。

需要在本机通过页面配置虚拟设备并联调平台时，使用独立的 [矿山设备模拟台](mine-device-simulator-app/README.md)。

单机一体化环境的构建、配置、启动和验收步骤见 [deploy/README.md](deploy/README.md)。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。第三方软件仍遵循其各自许可证，发行时必须保留对应声明。
