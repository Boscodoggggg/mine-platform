# 09 部署、备份与现场运维

## 1. 部署原则

- Docker Compose 是唯一一期编排方式。
- 单机和五节点使用同一套镜像、配置键和数据迁移。
- 所有镜像在联网环境构建并打包，矿区可完全离线安装。
- 配置、secret、数据卷和镜像分离。
- 不使用 Nacos；服务地址由 Compose DNS、内部 DNS、VIP 和主机清单提供。
- 生产镜像使用不可变版本与 digest，不使用 latest。

## 2. 部署目录

~~~text
deploy/
├── compose/
│   ├── compose-standalone.yml
│   ├── compose-access.yml
│   ├── compose-data-primary.yml
│   ├── compose-data-standby.yml
│   └── compose-infra.yml
├── env/
│   ├── standalone.env.example
│   └── production.env.example
├── config/
│   ├── rabbitmq/
│   ├── nginx/
│   ├── prometheus/
│   ├── alloy/
│   └── app/
├── secrets.example/
├── scripts/
│   ├── install-offline.sh
│   ├── preflight.sh
│   ├── deploy.sh
│   ├── health-check.sh
│   ├── backup.sh
│   ├── restore.sh
│   ├── upgrade.sh
│   └── rollback.sh
├── manifests/
│   ├── images.lock
│   └── checksums.sha256
└── runbooks/
~~~

示例 secret 目录只能有字段说明，不能有默认生产密码。

## 3. 单机方案

~~~mermaid
flowchart TB
    N["Nginx + mine-web"]
    G["mine-gateway"]
    S["mine-server"]
    T["mine-telemetry"]
    R["RabbitMQ + MQTT"]
    P["PostgreSQL"]
    TS["TimescaleDB"]
    C["Redis"]
    O["S3兼容对象存储"]
    M["Prometheus/Grafana/Loki"]
    N --> S
    G --> R
    R --> T
    S --> P
    S -->|只读查询账号| TS
    T --> TS
    T --> C
    S --> O
    M -.采集.-> G
    M -.采集.-> S
    M -.采集.-> T
~~~

Compose profiles：

- core：Web、gateway、server、telemetry、RabbitMQ、PostgreSQL、TimescaleDB、Redis、对象存储。
- observability：Prometheus、Grafana、Loki、Alloy、Alertmanager。
- demo：模拟设备、示例协议、示例三维场景。

单机不部署：

- Keepalived。
- PostgreSQL/TimescaleDB 备库。
- RabbitMQ 集群。
- Redis Sentinel。

单机是受支持的“非高可用生产模式”。应用容器自动重启不等于主机容灾。

## 4. 五节点生产基线

| 节点 | 服务 |
|---|---|
| node-1 | Keepalived、Nginx HTTPS/TCP stream、Web、gateway-1、server-1、telemetry-1 |
| node-2 | Keepalived、Nginx HTTPS/TCP stream、Web、gateway-2、server-2、telemetry-2 |
| node-3 | RabbitMQ-1、业务 PostgreSQL 主、TimescaleDB 主、Redis 主、Sentinel-1 |
| node-4 | RabbitMQ-2、业务 PostgreSQL 备、TimescaleDB 备、Redis 从、Sentinel-2 |
| node-5 | RabbitMQ-3、Sentinel-3、Prometheus、Grafana、Loki、对象存储 |
| 外部存储 | NAS 或加密离线备份，不计入计算节点 |

节点 3/4 的数据库与 RabbitMQ 必须使用独立数据目录/磁盘、容器资源限制和 IO 监控，避免互相耗尽资源。

## 5. 网络端口原则

| 端口 | 来源 | 目标 | 说明 |
|---|---|---|---|
| 443 | 办公/调度网 | VIP/Nginx | HTTPS/WSS |
| 8883 | 设备网 | node-1/2 VIP → Nginx stream → RabbitMQ | MQTTS 透传；必要时启用 PROXY protocol 保留源地址 |
| 私有 TCP 端口 | 指定设备网段 | gateway | 按协议白名单 |
| 5671 | 应用节点 | RabbitMQ | AMQPS |
| 5432 | 应用/备份节点 | PostgreSQL/TimescaleDB | 数据库 TLS |
| 6379 或 TLS 端口 | 应用节点 | Redis | ACL/TLS |
| 9090/3000 等 | 运维网 | 监控节点 | 不向设备网开放 |
| 18081/18082 | 容器健康检查/监控网 | gateway/telemetry 管理端口 | 默认仅监听 127.0.0.1；Compose 接入隔离监控网时显式改为 0.0.0.0，禁止发布到设备网和办公网 |

真实端口以现场网络设计为准；默认拒绝未列端口。

## 6. 容器要求

每个应用容器必须：

- 非 root 用户运行。
- 只读 root filesystem（确需临时目录时挂 tmpfs）。
- 声明 CPU、内存和日志限制。
- 提供 startup、liveness 和 readiness 健康接口。
- 使用 init 处理信号和僵尸进程。
- 优雅关闭。
- 使用本地时区只做展示，应用内部 UTC。
- 不把业务日志写入无限增长容器文件。
- Java 服务不得直接运行宿主机 `target/*.jar` 单文件挂载。Compose 只读挂载稳定的 `target/` 目录，启动时复制并校验
  完整 JAR 到容器内临时文件系统后再启动 JVM；运行期间 Maven 重打包不得改变当前进程 classpath。生产发布优先使用
  带 digest 的不可变应用镜像。

数据库容器要有明确 uid/gid、挂载权限和数据卷检查，升级前验证镜像与卷格式兼容。

## 7. 配置

配置分层：

~~~text
镜像默认值
→ 环境公共配置
→ 主机角色配置
→ secret文件
→ 平台动态策略
~~~

边界：

- 数据库地址、线程数、JVM、监听端口属于部署配置。
- 物模型、协议、映射、告警和保留属于平台策略。
- secret 只能由 secret 文件/受控环境注入。
- gateway 访问 RabbitMQ 使用两个独立密码文件：`rabbitmq_gateway_device_password` 只读设备上行队列，
  `rabbitmq_gateway_platform_password` 只写平台遥测交换机；不得复用 RabbitMQ 管理员密码。
- `mine-telemetry` 使用 `TIMESCALE_USER` 完成迁移和写入；`mine-server` 必须使用独立的
  `TIMESCALE_READER_USER` 与 `timescale_reader_password`，数据库仅授予 CONNECT、schema USAGE 和遥测表 SELECT。
- `timescale-reader-bootstrap` 在每次 Compose 启动时幂等创建/授权只读账号并应用密码轮换，兼容已有数据卷；
  `mine-server` 与 `mine-telemetry` 必须等待该任务成功。角色通过数据库注释标识为平台托管账号；同名但没有标识的
  现场账号一律拒绝接管。脚本会清除托管角色的高权限属性、角色成员关系和全部非系统 schema 的已有对象权限，
  再只授予 CONNECT、public USAGE 与表 SELECT；如果角色已拥有业务对象则拒绝启动，必须先人工迁移所有权。
  只读账号禁止与 Timescale 写入账号或 `postgres` 同名。
- `mine-server` 的 `telemetryQuery` readiness 不只执行连通性探测，还会以只读账号访问
  `telemetry_inbox.ingest_id`、`telemetry_numeric.ingest_id`、已提交水位表和三个持久最新值表。迁移未完成或授权缺失时服务保持
  not-ready，Nginx 不得向其转发流量。
- `mine-telemetry` 账号在读取 `mine.telemetry.q` 之外只允许写 `mine.realtime.x`；`mine-server` 使用独立
  `mine-server-realtime` 账号，每个实例必须配置唯一 `MINE_REALTIME_INSTANCE_ID`，并只允许创建、绑定和读取
  `mine.realtime.server.<instanceId>.q`。该队列是 classic、非持久、独占、自动删除队列，用于广播到每个服务实例，
  不能改成多实例共享队列，否则浏览器会随机漏掉实时增量。
- WSS 反向代理必须转发 `Upgrade` 和 `Connection` 头。浏览器 25 秒应用层心跳只维护连接，不执行 Inbox、Outbox
  或业务表扫描。

所有服务启动时输出“非敏感有效配置摘要”和版本，不打印 secret。

## 8. 服务发现

- 单机：Compose service name。
- 五节点：内部 DNS 名称或静态主机清单。
- 入口：Keepalived VIP + Nginx。HTTPS/WSS 使用 HTTP 代理；MQTTS 使用 TCP stream 代理到 RabbitMQ 三节点。
- RabbitMQ：客户端配置三个节点地址并自动恢复。
- 数据库：应用使用当前主库内部名；一期主库故障由运维确认后切换 DNS/配置。
- Redis：Sentinel-1/2/3 分布在 node-3/4/5，通过多数派发现并切换主节点。

不用 Nacos，不在代码里硬编码 IP。

## 9. 数据库高可用

一期：

- PostgreSQL 和 TimescaleDB 采用流复制。
- 自动监测复制延迟和 WAL 积压。
- 主库故障由运维确认后提升备库，避免错误自动双主。
- 恢复旧主前执行时间线和数据一致性检查。
- 切换脚本必须可重复运行并有明显确认提示。

后续只有在 RTO 确实要求分钟级自动切换时，再评估 Patroni/专用协调组件；不因“高可用”名义增加未掌握组件。

## 10. 故障行为

| 故障 | 预期 |
|---|---|
| node-1 故障 | VIP 到 node-2，浏览器/设备重连 |
| 单个 gateway 故障 | MQTT Broker 保持，其他 gateway 消费 |
| 单个 telemetry 故障 | RabbitMQ 未 ACK 消息重新投递 |
| 一个 RabbitMQ 节点故障 | 剩余两个多数派继续 |
| PostgreSQL 主库故障 | 人工提升备库并切换连接 |
| Redis 主故障 | Sentinel 切换，从历史/新消息修复最新值 |
| node-5 故障 | 消息多数派仍在，监控/对象服务暂时降级 |
| 单机主机故障 | 整个平台中断，从外部备份恢复 |

## 11. RPO/RTO

| 数据 | 五节点 | 单机 |
|---|---|---|
| 用户、设备、配置 | RPO ≤5 分钟，RTO ≤30 分钟 | RPO ≤15 分钟，RTO ≤4 小时 |
| 命令、告警、作业、审计 | RPO ≤5 分钟，RTO ≤30 分钟 | RPO ≤15 分钟，RTO ≤4 小时 |
| 遥测、UWB | RPO ≤15 分钟，RTO ≤2 小时 | RPO ≤30 分钟，RTO ≤4 小时 |
| Redis 缓存 | 可重建 | 可重建 |
| 协议样本、模型、附件 | RPO ≤24 小时，RTO ≤4 小时 | 同左 |

这是验收目标，不是只写在文档中的口号；恢复演练要记录实际时间和丢失窗口。

## 12. 备份

- PostgreSQL/TimescaleDB：持续 WAL 归档 + 每日基础备份。
- 对象存储：每日增量/快照。
- RabbitMQ：定义、用户、权限和策略导出；消息本身不当长期备份。
- Redis：不作为必须恢复的数据真相，可保留 RDB/AOF 加速但不计入核心 RPO。
- 配置包：数据库备份外，支持脱敏导出。

保留建议：

- 7 个日备份。
- 4 个周备份。
- 12 个按月备份。

至少三份：

1. 生产副本。
2. 现场 NAS 副本。
3. 加密离线或异地副本。

每周自动恢复校验关键表和 Timescale 扩展版本；每季度完整演练。

## 13. 升级

TimescaleDB 的 PostgreSQL major 版本升级不是普通容器重建。PG17 数据目录不得直接交给 PG18；必须先备份，
再通过 `pg_upgrade` 或逻辑备份/恢复迁移，并验证 `timescaledb.license=apache`、扩展版本、hypertable、索引、
行数和抽样查询结果。新建矿区直接使用当前 PG18 OSS 基线，已有矿区单独编写并演练升级方案。

升级包包含：

- 镜像 tar。
- images.lock 和 digest。
- Flyway migration。
- 配置变更说明。
- SBOM、许可证和校验和。
- 升级/回滚脚本。

流程：

1. preflight 检查磁盘、备份、复制、队列积压和版本。
2. 暂停高风险操作。
3. 备份并记录恢复点。
4. 多节点先升级备用实例，再滚动应用节点。
5. 数据库 migration 使用 expand/contract 兼容策略。
6. 执行 smoke test 和指标观察。
7. 成功后解除维护状态；失败按回滚手册处理。

数据库不可逆迁移必须有单独恢复方案，不能承诺简单回退镜像即可恢复。

## 14. 离线安装

离线包必须在与现场同架构的干净环境验证：

- 不访问公网镜像仓库、npm、Maven 或地图服务。
- 所有前端字体、图标、Cesium 资源和地图/模型本地化。
- install-offline 导入镜像并验证 digest。
- 初始化 secret 和证书。
- preflight 检查 Docker、Compose、内核参数、文件句柄、时间同步和磁盘。
- 生成部署报告和默认访问地址。

## 15. 监控与日志

组件：

- Prometheus：指标。
- Grafana：工程仪表板。
- Alertmanager：基础设施告警。
- Grafana Alloy + Loki：日志。
- Tempo/OpenTelemetry trace 一期预留 traceId，不部署完整追踪。

应用日志：

- JSON 结构化。
- timestamp、level、service、instance、traceId、eventId、deviceCode（仅日志字段）。
- 不记录密码、token、完整个人敏感信息。

Prometheus 不把 deviceId、commandId、traceId 当 label，避免高基数。

## 16. 运维页面和 Grafana 边界

平台运维页面展示简化状态：

- 服务在线、版本、配置版本。
- 设备连接、消息速率、队列积压。
- 数据库和磁盘状态。
- 备份任务和最近恢复演练。

Grafana 用于工程深入诊断，不把全部 PromQL 和内部细节复制到业务前端。

## 17. 现场巡检

每日：

- 服务和节点状态。
- MQTT 连接/认证失败。
- RabbitMQ 积压和磁盘水位。
- 数据库复制延迟、WAL 和磁盘。
- 备份结果。
- 解析失败和未知协议。

每周：

- 恢复校验。
- DLQ 处置。
- 证书/资质/磁盘容量趋势。
- 安全和漏洞更新评估。

每季度：

- 完整备份恢复。
- 节点故障和主备切换。
- 弱网和重连风暴抽测。
- 升级/回滚演练。

## 18. 容量规划

设备数量之外必须收集：

~~~text
消息速率 = 设备数 × 每设备每秒消息数
入站带宽 = 消息速率 × 平均报文大小
历史点速率 = 消息速率 × 每报文历史属性数
日数据量 = 每秒写入字节 × 86400 × 索引/副本/膨胀系数
~~~

硬件采购前用真实样本执行基准测试。任何 CPU、内存和磁盘配置都只能作为测试起点，不能替代容量报告。
