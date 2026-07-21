# 智慧矿山数据采集管理平台设计文档集

> 临时工程名：mine-platform  
> 文档版本：0.1.0  
> 基线日期：2026-07-14  
> 状态：已确认设计，可进入工程初始化  
> 部署形态：单矿区私有化部署，支持单机与五节点生产方案

工程坐标：Maven groupId 使用 `com.bosco`，Java 基础包使用 `com.bosco.mine`。  
项目许可证：Apache License 2.0。

## 1. 文档用途

本目录是 mine-platform 的产品、架构、开发、测试和运维基线。它不是一份仅供评审的概念方案，而是后续 Codex 生成代码、数据库迁移、接口、测试、Compose 文件和现场手册时必须遵守的上位约束。

项目名称暂不确定，源码中的 mine-* 仅作为稳定的临时模块名。更换产品名称不得改变模块边界、接口语义和数据库前缀。

## 2. 核心结论

- 平台从头自研，不基于现有 IoT 平台做二次开发。
- 允许使用 Spring Boot、Netty、RabbitMQ、PostgreSQL、TimescaleDB、Redis、Vue 等基础开源框架或中间件。
- 一套代码同时支持单机 Compose 和五节点 Compose，不维护两个产品分支。
- 设备接入以 MQTT 为主，同时支持厂商私有 TCP、二进制和差异化 MQTT 报文。
- MQTT Broker 与内部消息队列使用同一套三节点 RabbitMQ 集群，通过两个 vhost 做逻辑隔离。
- 普通协议采用前台配置；复杂加密、状态机、文件传输等通过 Java 协议插件扩展。
- 物模型、字段映射、协议、告警、保留、弱网等策略可在前台发布和热加载。
- 后端采用模块优先的 MVC，复杂领域局部使用领域模型，不实施教科书式全量 DDD。
- 前端基于 SoybeanAdmin 的布局、登录、主题和动态路由底座，矿山业务页面全部自研。
- 一期必须通过 5000 客户端压测、弱网补传测试和 72 小时稳定性测试。

## 3. 推荐阅读顺序

| 顺序 | 文档 | 读者与用途 |
|---|---|---|
| 1 | [00-product-scope.md](00-product-scope.md) | 了解产品目标、范围和不可变约束 |
| 2 | [01-system-architecture.md](01-system-architecture.md) | 了解模块、节点和主数据流 |
| 3 | [02-repository-backend.md](02-repository-backend.md) | 后端工程结构和编码边界 |
| 4 | [03-device-access-protocol.md](03-device-access-protocol.md) | MQTT、TCP、协议引擎和弱网 |
| 5 | [04-device-model-strategy.md](04-device-model-strategy.md) | 物模型、映射、策略和热发布 |
| 6 | [05-messaging-telemetry-data.md](05-messaging-telemetry-data.md) | RabbitMQ、遥测、时序与幂等 |
| 7 | [06-mine-business.md](06-mine-business.md) | 车辆、人员、路线、UWB 和运营 |
| 8 | [07-frontend.md](07-frontend.md) | Vue 3 信息架构和实时前端 |
| 9 | [08-security-auth-audit.md](08-security-auth-audit.md) | Sa-Token、RBAC、设备安全和审计 |
| 10 | [09-deployment-operations.md](09-deployment-operations.md) | 单机、五节点、备份和现场运维 |
| 11 | [10-testing-quality-gates.md](10-testing-quality-gates.md) | 测试分层、压测和上线门禁 |
| 12 | [11-contracts-and-database.md](11-contracts-and-database.md) | API、消息信封和核心表设计 |
| 13 | [12-roadmap-and-codex-workflow.md](12-roadmap-and-codex-workflow.md) | 阶段路线与 Codex 协作方式 |
| 14 | [13-technology-and-licenses.md](13-technology-and-licenses.md) | 技术版本、许可证和升级策略 |
| 15 | [14-decisions-and-glossary.md](14-decisions-and-glossary.md) | ADR 摘要、术语和待确认项 |

详细实施任务位于 plans/，必须按测试先行、小提交、逐阶段验收执行。

阶段验收结果位于 `reports/`。当前 [Phase 0 工程底座验收报告](reports/phase-0-acceptance.md) 为待复验状态；
[Phase 1 弱网补传自动化验证报告](reports/phase-1-weak-network.md) 已通过确定性自动化基线；
[Phase 1 首个真实设备闭环验收报告](reports/phase-1-acceptance.md) 因缺少真实设备和现场窗口保持阻塞，未闭环项不得按生产就绪处理。
Phase 2 的 [数据质量策略运行消费验收报告](reports/phase-2-data-quality-acceptance.md) 已通过非现场自动化基线；生产排障与回退遵循
[数据质量策略运行手册](runbooks/data-quality-runtime.md)，该结论不替代真实设备、矿区网络、容量、HA 或 24/72 小时长稳验收。
Phase 2 的 [双来源告警运行与处置验收报告](reports/phase-2-alarm-runtime-acceptance.md) 也已完成非现场自动化基线；发布观察、积压排查、人工处置和安全回退遵循
[告警运行与处置手册](runbooks/alarm-runtime.md)。告警策略、可靠事件和处置中心可用于继续产品化开发，但通知送达、现场阈值、值班演练及生产放行仍是明确延期项。
通知渠道控制面和七种平台内置 Provider 的数据库配置、加密与非现场请求映射遵循
[通知 Provider 配置与安全运行手册](runbooks/notification-providers.md)。当前结论不包含升级策略、运行投递账本、异步回执、事件单、前端工作台或任何真实短信、电话、邮件和企业 IM 送达证明。
Phase 2 的 [数据保留策略运行闭环验收报告](reports/phase-2-retention-runtime-acceptance.md) 已完成非现场自动化基线；影响预览、暂停、执行事实、告警处置和安全恢复遵循
[数据保留运行手册](runbooks/retention-runtime.md)。策略回滚不能恢复已删除数据，真实数据 dry-run、备份恢复、容量、HA 和长稳仍是生产放行前置条件。
Phase 2 的 [数值聚合运行闭环验收报告](reports/phase-2-aggregation-runtime-acceptance.md) 已完成非现场自动化基线；策略发布、版本化事实、冷热查询、受控重算、保留覆盖保护和故障恢复遵循
[数值聚合运行手册](runbooks/aggregation-runtime.md)。真实数据对账、容量基线、主备切换、备份恢复和 24/72 小时长稳仍须在现场完成，自动化通过不得解释为生产放行。
Phase 2 的 [TCP 二进制插件纵向样例验收报告](reports/phase-2-tcp-binary-acceptance.md) 已完成稳定 SPI、矿卡黄金协议、可信身份、可靠 ACK、TLS/限流/指标和部署告警的非现场自动化基线；
启用、模拟、告警、升级与回退遵循 [TCP 二进制 Gateway 运行手册](runbooks/tcp-binary-gateway.md)。真实厂商协议、原生 mTLS/设备证书身份、矿区网络、容量、双 Gateway 和长稳仍是生产阻断项。
Phase 3 的 [矿区定位纵向闭环自动化验收报告](reports/phase-3-mine-location-acceptance.md) 已覆盖矿区作用域、资产、发生时绑定、双坐标、固定定位接入、历史/当前位置/实时链路、前端工作台和确定性模拟；
运行准备、模拟场景、指标、告警和安全回退遵循 [矿区定位运行与模拟验收手册](runbooks/mine-location-runtime.md)。
Phase 3 的 [路线、围栏与空间事件自动化验收报告](reports/phase-3-spatial-event-acceptance.md) 已覆盖版本化空间主数据、快照、事件时间排序、边界消抖、进入/离开/停留、可靠事件、查询、实时增量、确定性模拟与受控重放；
发布、模拟、核查、告警和安全回退遵循 [矿区路线、围栏与空间事件运行手册](runbooks/mine-spatial-event-runtime.md)。
Phase 3 的 [班次、作业单与生产运营自动化验收报告](reports/phase-3-production-operation-acceptance.md) 已覆盖班次、运输调度、空间事件自动识别、异常候选、不可变事实与追加修正、固定水位统计、可靠实时、确定性模拟和运行告警；
值班操作、对账、故障处置和安全停启遵循 [矿区生产运营运行手册](runbooks/mine-production-operation-runtime.md)。
Phase 3 的 [三维场景自动化验收报告](reports/phase-3-scene-acceptance.md) 已覆盖安全内容寻址资源、场景草稿/发布/默认、完整运行快照、Cesium 离线懒加载、位置/空间/告警/运营叠加、确定性 GLB 和二维独立降级；
上传、发布、告警、对象对账、备份边界和回退遵循 [矿区三维场景运行与安全回退手册](runbooks/mine-scene-runtime.md)。Phase 3 仓库自动化范围已收口，下一阶段进入 Phase 4 商用加固；真实 UWB、模型/坐标/围栏和运营参数标定、GPU/浏览器、共享 S3、真实吨位、容量、HA、备份恢复、弱网和长稳均为 `DEFERRED` 生产阻断项。
现场执行必须遵循 [MQTT 真实设备现场接入与 24 小时验收手册](runbooks/device-onboarding.md)，并按
[Phase 1 真实设备验收记录契约](contracts/phase-1-device-acceptance-v1.schema.json) 留存结构化证据。

## 4. Codex 开始开发前的固定动作

1. 读取仓库根目录 AGENTS.md。
2. 读取本 README 和当前任务涉及的专题文档。
3. 检查 plans/ 中对应阶段的任务和验收标准。
4. 检查工作树已有改动，不覆盖用户修改。
5. 先写失败测试，再写最小实现。
6. 运行当前模块测试和架构规则。
7. 更新接口、消息、数据库及运维文档。
8. 使用中文 Conventional Commit 提交。

## 5. 文档治理

- 架构边界变更必须先增加或更新 ADR。
- API、MQ 消息和数据库结构属于契约，禁止只改实现不改文档。
- 协议版本、物模型版本和策略版本一经发布不可原地修改。
- 文档里的示例 ID、密钥和报文均不得使用现场真实凭据。
- 版本表中的 patch 版本只是当前验证基线，构建时必须由 BOM、lockfile 和镜像 digest 锁定。

## 6. 当前仍需现场材料

这些材料不阻塞工程底座，但会阻塞真实设备验收：

- 至少一种真实 MQTT 设备的连接参数、主题、上行与下行样本。
- 至少一种私有 TCP 二进制协议文档及正常、异常报文。
- UWB 厂商协议、频率、坐标系和精度定义。
- 矿区坐标基准、车辆/人员编码规范、班次和作业单规则。
- 三维模型来源；没有正式模型时使用演示 GLB/3D Tiles 和二维网格降级。
- 最终项目名称和对外品牌规范。

## 7. 一句话总结

mine-platform 是一个面向弱网矿区、支持异构设备和可配置协议、能够在现场长期运行的自研采集与业务平台，而不是一个换皮的通用 IoT 后台。
