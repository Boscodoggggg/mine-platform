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
| 9000（示例，可配置） | 指定设备网段 | gateway | `UNIFIED` 时一个 TLS 端口按产品路由多个插件；`LEGACY` 时一个监听器绑定一个插件；宿主默认仅发布到 127.0.0.1 |
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
- 物模型、协议、映射、告警、保留和数值聚合属于平台策略。
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
  不能改成多实例共享队列，否则浏览器会随机漏掉实时增量。RabbitMQ 执行 `queue.bind` 会校验交换机 read 与队列
  write 权限，因此该账号的 configure/write/read 正则都必须只覆盖实时交换机和本实例队列命名范围。
- `mine-server` 使用独立 `mine-server-command` 账号发布命令并消费状态，只授予 `/mine-platform` 中
  `mine.command.x` 的写权限和 `mine.server.command-status.q` 的读权限，不授予配置权限或其他队列权限。每台服务实例必须设置唯一
  `MINE_COMMAND_OUTBOX_INSTANCE_ID`；启动和 RabbitMQ 重连只做一次有界恢复，运行期间禁止定时扫描 Outbox 表。状态消费者使用手工
  ACK，必须监控 `mine.server.command-status.q` 的消费者数、ready/unacked 消息数和死信量；消费者数为零时发布健康告警。
- `mine-gateway-device` 只允许读取 `mine.gateway.uplink.q`、`mine.gateway.command-ack.q` 并写 `amq.topic`；
  `mine-gateway-platform` 只允许读取 `mine.gateway.command.q` 并写 `mine.telemetry.x`、`mine.command.x`。
  三条命令队列分别由设备 ACK、gateway 下发和 server 状态消费者拥有，不能合并成通配队列。
- `mine.command.timeout.wait.q` 使用单消息 TTL，过期后固定死信到 `command.status.timeout`；它替代命令超时扫表和
  进程内易丢计时器。必须监控该队列 ready 数、最老消息年龄和死信速率。
- 从旧拓扑升级时，启动脚本会检查旧 `mine.command.q`。队列为空才自动删除；只要存在消息就中止 RabbitMQ 启动，
  必须先由运维导出、确认语义并重放到 `mine.gateway.command.q`，禁止为了升级直接清空。
- WSS 反向代理必须转发 `Upgrade` 和 `Connection` 头。浏览器 25 秒应用层心跳只维护连接，不执行 Inbox、Outbox
  或业务表扫描。

所有服务启动时输出“非敏感有效配置摘要”和版本，不打印 secret。

### 7.1 数据保留调度参数

数据保留在应用默认配置中关闭，单机 Compose 显式开启。多 Telemetry 实例可以同时开启，但必须使用不同实例标识；数据库 advisory lock 会让同一 TimescaleDB 每轮只有一个实例真正执行。

| 环境变量 | 默认值 | 安全边界与说明 |
|---|---:|---|
| `MINE_TELEMETRY_RETENTION_ENABLED` | 应用 `false`；单机 Compose `true` | 是否创建调度器；紧急停删必须先同时停止/缩容全部旧实例并确认锁释放，再以 `false` 启动，禁止使用滚动重启代替停机 |
| `MINE_TELEMETRY_RETENTION_INSTANCE_ID` | Telemetry 实例/主机名 | 1～128 字符，必须能定位执行节点 |
| `MINE_TELEMETRY_RETENTION_BATCH_SIZE` | `10000` | 每数据集每批 1～100000 行 |
| `MINE_TELEMETRY_RETENTION_MAX_CYCLES` | `10` | 单轮双数据集周期 1～1000 |
| `MINE_TELEMETRY_RETENTION_INITIAL_DELAY` | `30s` | 0～24 小时 |
| `MINE_TELEMETRY_RETENTION_INTERVAL` | `5m` | 固定延迟 10 秒～24 小时 |
| `MINE_TELEMETRY_RETENTION_STALE_TIMEOUT` | `2h` | 失联执行收敛阈值 1 分钟～7 天 |

批次和周期上限是数据库保护参数，不是吞吐承诺。调整前必须结合候选积压、磁盘、WAL、锁等待、查询计划和备份窗口；不得为追赶积压直接提高到最大值。标准操作、暂停和恢复步骤见 [数据保留运行手册](runbooks/retention-runtime.md)。

### 7.2 数值聚合与显式重算参数

数值聚合和显式重算在应用默认配置中关闭，单机 Compose 显式开启。多 Telemetry 实例必须使用不同实例标识；常规物化与显式重算共用数据库 advisory lock。每轮常规物化退出后最多认领一个重算请求，避免历史修复挤占实时遥测资源。

| 环境变量 | 默认值 | 安全边界与说明 |
|---|---:|---|
| `MINE_TELEMETRY_AGGREGATION_ENABLED` | 应用 `false`；单机 Compose `true` | 是否创建聚合调度器；关闭后同时停止常规物化和重算消费 |
| `MINE_TELEMETRY_AGGREGATION_INSTANCE_ID` | Telemetry 实例/主机名 | 1～128 字符，写入不可变执行事实和控制面租约 |
| `MINE_TELEMETRY_AGGREGATION_BATCH_SIZE` | `1000` | 单批最多提交 1～10000 个聚合桶 |
| `MINE_TELEMETRY_AGGREGATION_MAX_CYCLES` | `10` | 单轮最多 1～1000 个批次周期 |
| `MINE_TELEMETRY_AGGREGATION_INITIAL_DELAY` | `30s` | 0～24 小时 |
| `MINE_TELEMETRY_AGGREGATION_INTERVAL` | `1m` | 固定延迟 10 秒～24 小时 |
| `MINE_TELEMETRY_AGGREGATION_STALE_TIMEOUT` | `2h` | 计划执行失联收敛阈值 1 分钟～7 天 |
| `MINE_TELEMETRY_AGGREGATION_RECOMPUTE_ENABLED` | 应用 `false`；单机 Compose `true` | 是否从管理服务认领显式重算；依赖策略分发 server URL、请求超时和 service token 文件 |
| `MINE_TELEMETRY_AGGREGATION_RECOMPUTE_LEASE` | `10m` | 单次租约 1 分钟～1 小时；执行超过租约会由稳定执行标识恢复，不得手工改 token 或终态 |

批次、周期、间隔和租约是资源保护参数，不是性能承诺。生产调整前必须使用真实数据分布验证桶积压、查询计划、WAL/IO、磁盘、并发历史查询和重算时长。操作、告警、暂停和恢复见 [数值聚合运行手册](runbooks/aggregation-runtime.md)。

### 7.3 TCP 二进制 Gateway 参数

TCP 二进制监听器在应用默认配置中关闭，Standalone Compose 为可复现纵向样例显式开启。容器内部监听全部接口，
宿主发布地址默认 `127.0.0.1`；只有设备网防火墙、来源白名单、证书、身份快照和告警全部就绪后，才能把
`MINE_GATEWAY_TCP_PUBLISHED_ADDRESS` 改为设备网地址。

| 环境变量 | 应用/Standalone 默认值 | 安全边界与说明 |
|---|---:|---|
| `MINE_GATEWAY_TCP_ENABLED` | 应用 `false`；Standalone `true` | 是否创建监听器；未知插件、无可靠发布器或配置非法会阻止 Gateway 启动 |
| `MINE_GATEWAY_TCP_MODE` | `LEGACY` | `UNIFIED` 使用固定登录帧按产品选插件并强制 TLS；`LEGACY` 用于不能改造的设备并要求静态协议键 |
| `MINE_GATEWAY_TCP_BIND_HOST` | `0.0.0.0` | 容器内绑定地址；宿主暴露范围由 Compose published address 与防火墙共同控制 |
| `MINE_GATEWAY_TCP_PORT` | `9000` | 0 仅供自动化随机端口；生产使用 1～65535 的固定受控端口 |
| `MINE_GATEWAY_TCP_PROTOCOL_KEY` | `haul-truck-binary` | 仅 `LEGACY` 使用；监听器绑定一个已离线审核且唯一的 ServiceLoader 插件键，`UNIFIED` 必须留空 |
| `MINE_GATEWAY_TCP_WORKER_THREADS` | `2` | Netty IO 线程 1～128，不执行同步 publisher confirm |
| `MINE_GATEWAY_TCP_BUSINESS_THREADS` | `4` | 每连接保持有序的解析与可靠发布线程 1～256 |
| `MINE_GATEWAY_TCP_BACKLOG` | `1024` | 内核接受队列 16～65535，不是可承诺并发量 |
| `MINE_GATEWAY_TCP_MAX_CONNECTIONS` | `10000` | 全局活动连接硬上限 1～1000000；生产值必须来自容量报告 |
| `MINE_GATEWAY_TCP_MAX_CONNECTIONS_PER_IP` | `100` | 单来源地址上限，不得大于全局上限；经四层代理时必须先验证源地址保留 |
| `MINE_GATEWAY_TCP_READ_IDLE_SECONDS` | `120` | 1～86400 秒无入站字节后关闭会话 |
| `MINE_GATEWAY_TCP_SHUTDOWN_TIMEOUT` | `30s` | 1 秒～2 分钟，先停监听并关闭活动连接，再停止业务和 IO 线程 |
| `MINE_GATEWAY_TCP_TLS_ENABLED` | `false` | 明文只允许回环或受控内网演示；生产必须为 `true` |
| `MINE_GATEWAY_TCP_TLS_CERTIFICATE` | `/run/secrets/tcp_tls_certificate` | PEM 证书链绝对路径，通过只读 Compose/Kubernetes secret 挂载 |
| `MINE_GATEWAY_TCP_TLS_PRIVATE_KEY` | `/run/secrets/tcp_tls_private_key` | PEM 私钥绝对路径，不进入镜像、环境变量、日志或离线示例 |

证书和私钥只在监听器启动时加载，轮换必须走受控重启；当前没有原地热加载。`UNIFIED` 模式还要求完整产品协议快照和含 `tcpUsername`、`tcpSecretHash` 的设备身份快照；摘要使用 BCrypt，原始 secret 只在设备端保存。插件安装、身份快照、模拟器、告警、
暂停、升级与回退步骤见 [TCP 二进制 Gateway 运行手册](runbooks/tcp-binary-gateway.md)。

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
| 数值聚合执行实例故障 | advisory lock 随会话释放；常规失联事实由后续实例收敛，显式重算租约到期后复用稳定执行标识恢复 |
| 数据保留执行实例故障 | 数据库锁随会话释放，超时 `RUNNING` 事实由后续实例收敛为失败并重新扫描 |
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

数据保留策略回滚不恢复已删除的遥测或质量证据。恢复已删事实前必须先停止保留调度、冻结相关写入/查询切换方案，选择删除前恢复点并在隔离环境验证，再按矿区批准的恢复流程回灌或切换；未完成备份恢复演练不得把数据保留切片视为生产放行。

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

TCP 插件升级属于应用镜像升级，不支持把 JAR 直接复制进运行容器。协议 ABI、协议键和 `protocolVersion` 不变时可按 Gateway
实例滚动升级；协议版本变化时，旧新实例无法同时共享单一身份快照版本，必须使用新协议键/新端口并行迁移，或申请维护窗口完成
全量切换。失败时回退到上一不可变镜像和匹配的完整身份快照；已收到 ACK 的遥测不得由运维伪造重放，未收到 ACK 的设备应按原序号重传。

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

### 15.1 数据保留监控

至少观察以下指标和告警：

- `mine_telemetry_retention_runs_total{status=...}` 与 `mine_telemetry_retention_rows_deleted_total{dataset="raw|quality"}`；
- `mine_telemetry_retention_running`、`mine_telemetry_retention_policy_count`；
- `mine_telemetry_retention_consecutive_failures`、`mine_telemetry_retention_consecutive_partial`；
- `mine_telemetry_retention_last_success_epoch_seconds` 与 `mine_telemetry_retention_last_completion_epoch_seconds`；
- `MineTelemetryRetentionConsecutiveFailures`、`MineTelemetryRetentionNoRecentSuccess`、`MineTelemetryRetentionPersistentlyPartial`。

告警后的标准顺序是：确认是否仍在删除、检查最近执行事实与数据库健康、必要时停止调度、再处理积压或回滚策略。禁止为了消除告警删除执行事实、清零数据库状态或手工批量删除。

### 15.2 TCP 二进制接入监控

至少观察以下低基数指标和告警：

- `mine_gateway_tcp_listener_up{protocol=...}`、`mine_gateway_tcp_connections_active{protocol=...}`；
- `mine_gateway_tcp_connections{protocol=...,result="accepted|rejected"}`；
- `mine_gateway_tcp_frames{protocol=...,result="success|rejected",reason=...}`；
- `mine_gateway_tcp_publish_failures{protocol=...,result="failure"}` 与 `mine_gateway_tcp_acknowledgements{protocol=...,result="success"}`；
- `MineGatewayTcpListenerDown`、`MineGatewayTcpConnectionsRejected`、`MineGatewayTcpFramesRejected`、
  `MineGatewayTcpPublishFailures`、`MineGatewayTcpNoSuccessfulTelemetry`。

标签只能使用协议键、结果和固定原因，不得加入设备、IP、帧内容、异常文本或业务标识。告警处置顺序见
[TCP 二进制 Gateway 运行手册](runbooks/tcp-binary-gateway.md)，不得为消警放宽 CRC、身份、帧长或 publisher confirm 门禁。

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
