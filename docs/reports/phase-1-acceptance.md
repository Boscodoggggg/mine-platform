# Phase 1 首个真实设备闭环验收报告

> 报告日期：2026-07-15
>
> 验证分支：`feat/phase-0-foundation`
>
> 当前状态：**进行中——自动化和现有单机环境通过，真实设备 24 小时现场验收阻塞**
>
> 生产放行结论：**不放行**

## 1. 结论

Phase 1 已具备真实 MQTT 设备现场接入所需的平台闭环和可重复自动化基线：全仓质量门禁、单机 Compose、MQTTS、遥测入库、弱网补传逻辑、实时页面和低风险命令 E2E 均已通过。

当前没有真实设备连接参数、厂商报文、设备凭据、矿区测试窗口和现场网络条件，因此无法执行不少于 24 小时的真实设备运行，也无法得到真实消息率、失败率、端到端延迟、断线恢复、未知报文和命令回复数据。本报告将该项判定为 `BLOCKED`，不把模拟器或已运行的空载 Compose 环境冒充现场验收。

只有按 [真实设备现场接入与 24 小时验收手册](../runbooks/device-onboarding.md) 完成真实设备证据后，才能把本报告更新为 `PASS` 并讨论生产试运行。

## 2. 本次已执行门禁

| 验收项 | 命令 | 时间 | 结果 | 说明 |
|---|---|---|---|---|
| 全仓质量门禁 | `bash scripts/verify.sh` | 2026-07-15 20:07–20:09 CST | PASS | Maven、前端、离线包回归、配置、SBOM、许可证全部通过 |
| 单机部署冒烟 | `bash deploy/tests/standalone-smoke.sh` | 2026-07-15 19:23 CST | PASS | 核心服务、HTTPS、MQTTS、数据库、TimescaleDB、RabbitMQ、Redis 正常 |
| 全新数据卷离线安装 | `install-offline.sh --project-name mine-offline-acceptance` | 2026-07-15 19:55 CST | PASS | 1.3 GiB 包校验、镜像 ID、空库迁移、HTTPS、MQTTS 和数据层均通过 |
| 浏览器 E2E | `pnpm --dir mine-web exec playwright test` | 2026-07-15 19:24 CST | PASS | Chromium 4/4，通过实时监控和三种命令终态 |
| 弱网自动化 | Task 15 定向和完整 Maven 验证 | 2026-07-15 | PASS | 详见弱网补传自动化报告 |
| 真实设备 24 小时 | 待现场执行 | 未执行 | BLOCKED | 缺少真实设备、协议材料、凭据和现场窗口 |
| 真实设备命令闭环 | 待现场执行 | 未执行 | BLOCKED | 只能证明模拟器 E2E，不能证明真实设备回复 |

`standalone-smoke.sh` 中需要管理员密码的访问管理冒烟因为未设置 `MINE_SMOKE_ADMIN_PASSWORD` 被脚本明确跳过；该跳过不影响核心基础设施结果，但现场验收必须使用受控临时管理员凭据补跑并记录结果。

## 3. 自动化结果摘要

### 3.1 后端

10 个 Reactor 模块全部构建成功：

- `mine-common`：6 个单元测试。
- `mine-device-model`：4 个单元测试。
- `mine-messaging`：9 个单元测试、5 个 RabbitMQ 集成测试。
- `mine-gateway`：22 个单元测试、3 个 MQTT/命令集成测试。
- `mine-telemetry`：25 个单元测试、10 个 TimescaleDB/Redis/弱网集成测试。
- `mine-server`：单元/架构测试通过，Failsafe 汇总 61 个集成测试通过。
- `mine-device-simulator`：8 个场景和运行时测试。

Maven Reactor 总耗时约 1 分 45 秒，所有模块 `SUCCESS`。测试日志中故障分支主动制造的异常以及 macOS Netty native DNS、Mockito 动态 agent 警告不属于测试失败；后续应在依赖治理中消除未来 JDK 兼容性警告。

### 3.2 前端

- TypeScript 类型检查通过。
- UI 治理 23 项测试通过，Oxlint/ESLint 0 warning、0 error。
- Vitest 15 个测试文件、78 项测试通过。
- 生产构建通过。
- Playwright 4 项通过：实时遥测卡更新、命令成功、设备拒绝、回复超时。

### 3.3 部署与供应链

- Shell 语法和 Compose 配置通过。
- Prometheus 及 5 条规则、Loki、Alloy 配置通过。
- 后端与前端 CycloneDX SBOM 生成成功。
- 未发现 AGPL、SSPL 或 Commons Clause 依赖。
- 单机栈 14 个容器健康，MQTTS、HTTPS 和数据层冒烟通过。

## 4. 当前运行环境快照不能替代真实设备验收

现有开发单机栈已运行约 25 小时，但 Java 服务在此期间有重建，且没有接入真实生产设备。因此它只能证明当前容器健康，不能作为“真实设备连续运行 24 小时”的证据。

2026-07-15 19:24 CST 的空载资源快照：

| 服务 | CPU | 内存 |
|---|---:|---:|
| mine-gateway | 0.12% | 214 MiB |
| mine-server | 0.37% | 317.1 MiB |
| mine-telemetry | 0.17% | 259.1 MiB |
| rabbitmq | 0.32% | 118.8 MiB |
| redis | 0.79% | 10.16 MiB |
| postgres | 0.00% | 53.09 MiB |
| timescale | 1.25% | 84.24 MiB |

当时 `/mine-platform` 的遥测、命令、状态、实时、配置和死信队列 `messages_ready=0`、`messages_unacknowledged=0`；这只说明采样时没有积压，不说明有真实消息负载。过去 24 小时 gateway/telemetry 日志筛选未发现未知/无法解析/ERROR 记录，同样因为没有真实设备数据，不能将未知报文率写成 0。

## 5. 真实设备必须补齐的证据

| 证据 | 当前值 | 完成要求 |
|---|---|---|
| 设备身份 | 缺失 | 厂商、型号、固件、平台设备 UUID、产品和设备编码 |
| 观察窗口 | 未开始 | 连续不少于 24 小时，包含开始/结束时间和责任人 |
| 消息负载 | 未测量 | 总量、平均/峰值速率、属性点率和字节率 |
| 可靠性 | 未测量 | 解析失败、重复、缺口、死信、未知报文的绝对数和比例 |
| 延迟 | 未测量 | 设备到平台、入库、前端的 p50/p95/p99/max |
| 弱网恢复 | 未执行 | 真实骤断、重连、积压峰值、补传完整性和清空时间 |
| 资源趋势 | 仅有单点空载快照 | 24 小时 CPU、内存、磁盘、队列和连接趋势 |
| 命令闭环 | 仅模拟器通过 | 真实设备 `QUERY_STATUS` 的 ACK、终态、回复和审计 |
| 现场证据 | 缺失 | 日志、指标、脱敏样本、截图和 SHA-256 清单 |

现场记录必须符合 `docs/contracts/phase-1-device-acceptance-v1.schema.json`，其中无流量时的分布值应为 `null`，不能填 0。

## 6. 遗留风险

1. 当前身份映射仍以 gateway 轮询热加载 JSON 快照为 Phase 1 兜底，尚未由前台配置发布链路完全托管；gateway 已提供版本单调保护和内部 `/actuator/info` 生效状态，但现场仍必须保留外部发布记录并完成功能探针。
2. 模拟器的 10 分钟离线是 Paho 优雅断开，不覆盖骤断、半开、丢包、抖动和基站切换。
3. 设备本地缓存、掉电保护、补传限速、序号回绕和时钟漂移只有厂商与现场才能验证。
4. 目前只发布低风险 `QUERY_STATUS`，且命令 UI/API 尚无业务原因字段，批准工单只能在外部证据中关联；生产控制命令仍需补齐原因审计、审批、双人复核、联锁和风险评估。
5. 单机部署没有主机级高可用；生产数据必须外部备份并演练恢复。
6. 当前离线包已锁定基础镜像 ID、文件 SHA-256、SBOM 和许可证，但三个应用仍以只读 JAR 运行；应用镜像、发行签名和现场架构机复验仍需在生产发布阶段固化。
7. 一期最终质量目标还包括 5000 客户端压测和 72 小时平台稳定性测试；本次 24 小时首设备验收不替代这两项。

## 7. 阶段决策

当前决定：

- 可以继续完善开发环境、协议适配工具和现场准备材料。
- 不得宣称 Phase 1 已生产验收完成，不得把当前版本直接投入矿区生产运行。
- 不应以赶进度为由跳过真实设备 24 小时闭环；进入生产试运行前必须关闭本报告的 `BLOCKED` 项。
- 获得真实设备材料后，按运行手册创建仓库外证据目录，执行完整窗口，再更新本报告结论和遗留问题。

关联文档：

- [Phase 1 弱网补传自动化验证报告](phase-1-weak-network.md)
- [MQTT 真实设备现场接入与 24 小时验收手册](../runbooks/device-onboarding.md)
- [Phase 1 真实设备验收记录契约](../contracts/phase-1-device-acceptance-v1.schema.json)
