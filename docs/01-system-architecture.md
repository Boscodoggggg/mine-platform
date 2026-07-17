# 01 总体系统架构

## 1. 架构风格

平台采用“少量独立运行进程 + 清晰源码模块 + 异步消息解耦”的结构。不是单体大包，也不拆成几十个微服务。

运行时主要包含：

- mine-gateway：设备连接、协议选择、初步鉴权和统一消息输出。
- mine-telemetry：遥测清洗、映射、幂等、告警、最新值和时序写入。
- mine-server：矿山业务、系统管理、策略控制面和对前端 API。
- mine-web：Vue 3 管理端。
- mine-edge：可选的车载或现场弱网代理，不是矿区中心必装服务。
- RabbitMQ、PostgreSQL/TimescaleDB、Redis、对象存储和监控组件。

mine-protocol-engine、mine-protocol-sdk、mine-device-model、mine-messaging 和 mine-common 主要是共享源码模块，不单独作为远程微服务调用。

## 2. 系统上下文

~~~mermaid
flowchart LR
    D["矿车/传感器/UWB/第三方设备"] -->|"MQTT / 私有 TCP"| R["RabbitMQ MQTT / mine-gateway"]
    E["可选 mine-edge"] -->|"弱网缓存与补传"| R
    R --> T["mine-telemetry"]
    T --> TS["TimescaleDB"]
    T --> C["Redis 当前值"]
    T --> B["PostgreSQL 业务库"]
    T -->|"实时/业务事件"| MQ["RabbitMQ /mine-platform"]
    MQ --> S["mine-server"]
    S --> B
    S --> O["S3 兼容对象存储"]
    S -->|"统一 WebSocket"| W
    W -->|"策略、协议、命令、业务管理"| S
    S -->|"命令事件"| MQ
    MQ --> R
    R -->|"MQTT 下行/TCP 响应"| D
~~~

## 3. 仓库结构

~~~text
mine-platform/
├── mine-edge/                 # 可选车载/现场弱网代理
├── mine-gateway/              # Netty、MQTT设备接入
├── mine-telemetry/            # 遥测处理、告警、时序入库
├── mine-server/               # 矿山业务和管理API
├── mine-web/                  # Vue 3管理端
│
├── mine-protocol-sdk/         # Java协议插件开发接口
├── mine-protocol-engine/      # 前台可配置协议解析引擎
├── mine-device-model/         # 产品、物模型和统一设备消息
├── mine-messaging/            # 消息信封、路由键和消费者基础设施
├── mine-common/               # 无业务归属的公共基础能力
│
├── deploy/                    # Docker Compose、离线包和运维脚本
├── docs/                      # 架构、接口、开发和运维文档
└── examples/                  # 示例设备、协议、插件和模拟器
~~~

## 4. 模块职责与禁止事项

| 模块 | 负责 | 不负责 |
|---|---|---|
| mine-gateway | 连接、会话、报文边界、协议路由、上下行适配 | 运营统计、长期历史查询 |
| mine-telemetry | 标准化、幂等、最新值、时序、告警计算 | 用户菜单、车辆档案 CRUD |
| mine-server | 系统管理、矿山业务、策略发布、命令编排 | 持有大量设备长连接 |
| mine-web | 管理、配置、监控、三维可视化 | 直接连接 RabbitMQ 或数据库 |
| mine-edge | 终端侧缓存、序号、补传、可选现场协议适配 | 代替中心 Broker 或业务库 |
| protocol-engine | 编译和执行声明式协议规则 | 任意脚本执行 |
| device-model | 统一消息、物模型类型和语义校验 | 网络连接和数据库访问 |
| messaging | 跨进程消息契约和幂等元数据 | 放置业务实体和通用工具大全 |

## 5. 三条主链路

### 5.1 遥测上行

1. 设备连接 RabbitMQ MQTT 或 mine-gateway TCP 监听器。
2. Broker/MQTT 消息由 gateway 消费，TCP 字节流由 Netty pipeline 拆帧。
3. gateway 依据设备、产品和已发布配置包选择协议执行计划。
4. 解析结果转换成 UnifiedDeviceMessage，并发布到 /mine-platform。
5. telemetry 校验 schema、做字段映射、去重、乱序判断和质量标记。
6. telemetry 在 TimescaleDB 单事务提交 Inbox、历史、持久化最新值和摄取水位，提交成功后刷新 Redis 最新值读模型。
7. telemetry 通过 publisher confirm 发布已持久化实时事件，成功后才 ACK 原始遥测消息；失败时依靠 Inbox 幂等重新投递。
8. 每个 server 实例用独占临时队列接收实时事件，并按设备和属性只推送给本机匹配的 WebSocket 订阅，不广播未订阅的高频原始点。

### 5.2 命令下行

1. 用户通过 mine-web 提交命令，mine-server 校验权限、参数、设备能力和危险级别。
2. 命令实例和 outbox 事件在一个数据库事务中创建。
3. outbox relay 发布命令消息到 RabbitMQ。
4. gateway 消费命令，使用当前协议版本编码并通过 MQTT/TCP 下发。
5. MQTT PUBACK 只代表 Broker 接收；设备 ACK/业务回复才推动“设备收到/执行完成”状态。
6. 超时任务推进超时状态；安全命令按模板决定是否重试。

### 5.3 配置发布

1. 管理员编辑物模型、协议、映射和策略草稿。
2. server 执行结构校验、语义校验、样本回归和影响分析。
3. 发布操作生成不可变配置包版本及 outbox 事件。
4. gateway/telemetry 拉取完整快照，编译后原子替换。
5. 节点上报加载结果；失败节点保留上一成功版本。
6. 回滚是把旧快照重新发布为一个新发布记录，不修改历史。

## 6. 控制面与数据面故障边界

- mine-server 故障：无法管理配置和业务，但 gateway/telemetry 使用最后成功快照继续采集。
- PostgreSQL 业务库短时故障：现有 MQTT 连接不应立即中断；需要持久化的控制操作失败并明确返回。
- TimescaleDB 故障：telemetry 触发背压和有限重试，消息暂存 RabbitMQ；超过上限进入降级和告警，不能无限堆积。
- Redis 故障：当前值和会话受影响，历史写入可继续；恢复后从最新历史或新消息重建。
- 单个 RabbitMQ 节点故障：五节点方案中由剩余多数派继续；单机方案整体消息链路中断。
- 配置加载失败：拒绝半成品，继续运行上一成功快照。

## 7. 服务间通信

- 浏览器到 server：HTTPS REST。
- 浏览器实时数据：统一 WebSocket，按主题订阅。
- gateway、telemetry、server 异步协作：RabbitMQ /mine-platform。
- 少量同步查询：HTTP，只用于必须立即返回的控制面操作。
- 禁止服务直接访问其他服务私有表。
- 禁止业务模块跨模块调用对方 Mapper；使用公开 Service 接口或领域事件。

## 8. 为什么不用 Nacos

Compose 私有化部署的服务数量有限，服务地址稳定，不需要动态注册中心。配置由平台自己的策略控制面管理，不能与通用配置中心混为一谈。

- 单机通过 Compose DNS 服务名发现。
- 多机通过固定内部域名、VIP 或受管主机清单连接。
- 密钥通过 Docker secrets 或只读文件注入。
- 平台策略通过版本化配置包分发。

如果未来演进到大量动态微服务或 Kubernetes，再单独评估服务发现；当前不预埋 Nacos 依赖。

## 9. 部署视图摘要

五节点推荐：

- 节点 1/2：Keepalived VIP、Nginx HTTPS/TCP stream、Web、gateway、server、telemetry。8883 由 Nginx stream 转发到 RabbitMQ 节点。
- 节点 3/4：RabbitMQ 节点、业务 PostgreSQL 与 TimescaleDB 两套实例的主备、Redis 主从，并分别运行 Sentinel-1/2。
- 节点 5：RabbitMQ 第三节点、Sentinel-3、监控和对象存储。
- 外部 NAS/备份介质：不计入五台计算节点。

单机：

- 所有组件由 compose-standalone.yml 部署。
- 不启用 Keepalived、数据库备库、Redis Sentinel 和 RabbitMQ 集群。
- 明确标识“非高可用生产模式”。

## 10. 架构约束自动化

业务流程默认由请求、RabbitMQ 消息或数据库提交后的通知触发，禁止服务通过固定间隔高频扫描业务表推进状态。
Inbox 只能在消息消费事务中写入；Outbox relay 由事务提交通知唤醒并按事件标识处理。服务启动或数据库重连时允许执行一次
带索引、带批量上限的恢复查询，但不得演变成常驻轮询。命令超时、策略定时生效和重试优先使用 RabbitMQ 延迟/重试队列。
历史数值聚合和数据保留属于例外的低频受控后台任务：必须默认关闭、部署显式启用，使用数据库 advisory lock、固定水位、
有界批次、不可变执行事实和低基数告警；数据保留还必须以成功聚合覆盖为数据库级删除前置条件，不能依赖调度时间碰巧先后。

后端使用 ArchUnit 或等价架构测试保证：

- controller 只能依赖本模块 service、DTO 和公共接口。
- service 不得依赖其他模块 mapper。
- domain/model 不依赖 Spring MVC、MyBatis 实现和 AMQP 客户端。
- mine-common 不得引用任何业务模块。
- mine-device-model 和 mine-messaging 不得访问数据库。

前端通过 ESLint 边界规则保证业务模块只能通过 shared API、stores 和 contracts 协作，禁止相互导入内部页面组件。
