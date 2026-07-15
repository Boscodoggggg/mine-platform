# 单机一体化部署

本目录提供一期可执行的单矿区单机部署基线。它用于开发联调、现场验收和不要求主机级高可用的生产环境；一台主机故障会导致整个平台中断，因此生产数据必须备份到主机之外。

## 1. 当前包含的服务

| 服务 | 作用 | 是否发布到宿主机 |
|---|---|---|
| `nginx` | 管理端静态资源、HTTPS 与 API 反向代理 | `443`，可配置 |
| `rabbitmq` | 平台内部 AMQP 消息与设备 MQTTS 接入 | `8883`，可配置 |
| `mine-server` | 系统管理和矿山业务 API | 否 |
| `mine-gateway` | 设备接入和协议处理 | 否 |
| `mine-telemetry` | 遥测消费、处理和入库 | 否 |
| `postgres` | 业务数据 | 否 |
| `timescale` | 遥测时序数据 | 否 |
| `redis` | 会话、缓存和实时状态 | 否 |

启用 `observability` profile 后还会启动 Prometheus、Grafana、Loki、Alloy 以及 PostgreSQL/TimescaleDB exporter。Grafana `3000`、Prometheus `9090` 和 Loki `3100` 默认都只绑定宿主机 `127.0.0.1`。需要让运维工作站直接访问 Grafana 时，把 `MINE_GRAFANA_BIND_ADDRESS` 改为指定管理网 IP，并由现场防火墙限制来源；不要把它开放到设备网。

数据库、Redis、AMQP 管理端口和应用管理端口只存在于 Compose 隔离网络。现场防火墙只需按需开放 HTTPS 和 MQTTS，不要把数据库端口直接暴露到办公网或设备网。

## 2. 前置条件

- Linux x86_64/ARM64 主机，推荐至少 8 核、16 GiB 内存和 200 GiB 可用磁盘；最终容量应按设备数、上报频率和保留周期重新核算。
- Docker Engine 24+，Docker Compose v2。
- JDK 21、Node.js 20+ 和 pnpm，用于联网构建机编译制品。现场离线机只需要 Docker 和提前导入的镜像。
- 已确认 `443`、`8883` 未被占用，系统时间同步正常。

生产环境不要直接使用示例密码或临时自签名证书。

## 3. 构建制品

在项目根目录执行：

~~~bash
./mvnw clean verify
cd mine-web
pnpm install --frozen-lockfile
pnpm build
cd ..
~~~

Compose 直接以只读方式挂载三个可执行 JAR 和 `mine-web/dist`。正式离线发行后会进一步封装为带 digest 的应用镜像，但应用配置键和数据卷不会改变。

## 4. 初始化配置与 secret

~~~bash
cp deploy/env/standalone.env.example deploy/env/standalone.env
mkdir -p deploy/secrets
~~~

按 [secrets.example/README.md](secrets.example/README.md) 创建全部 secret 文件。每个密码文件只放一行随机值，私钥文件权限建议为 `0600`。现场证书应由矿区信任的 CA 签发，并包含浏览器访问域名和设备连接域名。

然后修改 `deploy/env/standalone.env`：

- 修改初始管理员用户名和显示名；初始密码在 secret 文件中配置。
- 需要避开现有端口时，修改 `MINE_HTTPS_PORT` 和 `MINE_MQTTS_PORT`。
- 镜像标签在发布前固定；生产离线包应额外记录镜像 digest。
- `MINE_TLS_SERVER_NAME` 必须与证书名称一致。

环境文件和 `deploy/secrets/` 已被 Git 忽略，不得提交仓库。

## 5. 启动与验收

先执行前置检查：

~~~bash
bash deploy/scripts/preflight.sh
~~~

启动：

~~~bash
docker compose \
  --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml \
  up -d
~~~

生产现场建议同时启动可观测组件：

~~~bash
MINE_PREFLIGHT_PROFILE=observability bash deploy/scripts/preflight.sh
docker compose \
  --profile observability \
  --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml \
  up -d
~~~

启动后执行完整健康检查：

~~~bash
bash deploy/scripts/health-check.sh
~~~

健康检查会等待全部容器健康，并验证 HTTPS API、MQTTS 证书、PostgreSQL、TimescaleDB 扩展、RabbitMQ 和 Redis。查看状态与日志：

~~~bash
docker compose --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml ps
docker compose --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml logs --tail=200 mine-server
~~~

TimescaleDB 基线固定为 PostgreSQL 18.4 + TimescaleDB 2.28.2 OSS，并在容器健康检查和冒烟测试中要求
`timescaledb.license=apache`。已经从旧示例复制过 `deploy/env/standalone.env` 的开发环境，需要同步更新
`MINE_TIMESCALE_IMAGE`；生产环境应改为现场离线仓库中复核过的同一 digest。

已有 PostgreSQL 17 Timescale 数据卷不能直接挂载给 PostgreSQL 18 镜像。升级前必须完成可恢复备份，使用
`pg_upgrade` 或逻辑备份/恢复迁移，并在独立环境验证扩展版本、hypertable、索引和数据量；禁止直接替换镜像碰运气。

可观测性单独验收：

~~~bash
MINE_ENV_FILE=deploy/env/standalone.env bash deploy/tests/observability-smoke.sh
~~~

该脚本验证三个 Java 服务、RabbitMQ、PostgreSQL 和 TimescaleDB 的 Prometheus target 均为 `up`，Grafana/Loki 可用，并生成一次 HTTPS 请求后按 `X-Request-Id` 在 Loki 中反查日志。Grafana 管理密码来自 `deploy/secrets/grafana_admin_password`，总览仪表板和 Prometheus/Loki 数据源会自动配置。

## 6. 停止与清理

正常停止但保留数据卷：

~~~bash
docker compose --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml down
~~~

不要在生产环境执行 `down -v`，它会删除 PostgreSQL、TimescaleDB、RabbitMQ 和 Redis 数据卷。确需销毁测试环境时，必须先确认没有生产数据并完成备份。

## 7. 配置变更边界

以下配置修改后通常需要重建容器：端口、数据库连接、JVM、镜像、证书和基础设施资源限制。

物模型、协议解析、字段映射、告警规则和历史数据保留策略属于平台动态策略，应在管理端完成版本化发布，不通过修改 Compose 文件实现，也不要求重启接入服务。

## 8. 生产注意事项

- 单机模式是非高可用模式。至少把数据库备份和配置导出复制到独立 NAS 或离线介质。
- RabbitMQ 同时承担设备 MQTT Broker 和平台内部消息总线，但通过虚拟主机、用户、权限和交换机隔离；不要再部署第二套 MQ。
- `mine-telemetry` 使用独立的 `mine-telemetry` 账号，只能读取平台遥测队列；Redis 与 TimescaleDB 均成功后才确认消息，任一侧故障都会触发重新投递。
- 实时最新值按设备保存在 Redis Hash 中且不设置统一 TTL，避免活动设备键同时过期；历史事实以 TimescaleDB 为准。进程内一级缓存只用于后续版本化物模型、协议和策略配置，并必须采用容量上限、随机抖动 TTL、发布事件失效与 single-flight 回源。
- 单机 Redis 默认在 512 MiB 容器限制内设置 `384mb + noeviction`。达到上限时写入明确失败并让 RabbitMQ 消息背压，不能静默驱逐 boot/sequence 状态；现场容量评估后可调整 `MINE_REDIS_MAXMEMORY`，但必须为进程和 AOF 留出余量，并对 Redis 内存、RabbitMQ 积压和拒绝写入告警。
- 弱网设备应使用持久会话、QoS、消息序号和幂等处理，命令必须具有超时、回执和审计记录。
- 发布新版本前先备份，再执行冒烟测试；数据库迁移必须使用向前兼容的 expand/contract 方式。
- 当前 Compose 不包含对象存储和主机容灾。五节点生产方案及完整 RPO/RTO 见 [部署与运维设计](../docs/09-deployment-operations.md)。
- Alloy 为采集容器日志只读挂载了 Docker socket；它仍具备读取 Docker 元数据和日志的高权限。必须使用固定可信镜像、限制 Grafana/Alloy 管理入口，并且不能给业务用户容器管理权限。
- 单机 Loki 使用本地文件系统并保留 30 天日志，Prometheus 默认保留 15 天指标；现场磁盘容量评估后再调整，不能把它们当作审计数据的唯一副本。
