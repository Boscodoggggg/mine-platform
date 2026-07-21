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
| `rustfs` | 开发与测试环境的 S3 兼容对象存储 | 否 |

启用 `observability` profile 后还会启动 Prometheus、Grafana、Loki、Alloy 以及 PostgreSQL/TimescaleDB exporter。Grafana `3000`、Prometheus `9090` 和 Loki `3100` 默认都只绑定宿主机 `127.0.0.1`。需要让运维工作站直接访问 Grafana 时，把 `MINE_GRAFANA_BIND_ADDRESS` 改为指定管理网 IP，并由现场防火墙限制来源；不要把它开放到设备网。

数据库、Redis、AMQP 管理端口和应用管理端口只存在于 Compose 隔离网络。现场防火墙只需按需开放 HTTPS 和 MQTTS，不要把数据库端口直接暴露到办公网或设备网。

## 2. 前置条件

- Linux x86_64/ARM64 主机，推荐至少 8 核、16 GiB 内存和 200 GiB 可用磁盘；最终容量应按设备数、上报频率和保留周期重新核算。
- Docker Engine 24+，Docker Compose v2。
- 联网构建机需要 JDK 21、Node.js 20+、pnpm、jq、Docker Engine 24+ 和 Docker Compose v2，用于编译制品、生成并校验 SBOM、归档本地镜像。
- 现场离线机需要 Bash、Docker Engine 24+、Docker Compose v2、curl、OpenSSL、jq，以及常见 Linux 基础命令：`awk`、`df`、`grep`、`sort`、`stat`、`seq` 和 `sha256sum`（也兼容 `shasum`）。安装器会在导入镜像前统一检查。
- 已确认 `443`、`8883` 未被占用，系统时间同步正常。

生产环境不要直接使用示例密码或临时自签名证书。

## 3. 构建制品

在项目根目录执行：

~~~bash
bash scripts/verify.sh
~~~

Compose 直接以只读方式挂载三个可执行 JAR 和 `mine-web/dist`。正式离线发行后会进一步封装为带 digest 的应用镜像，但应用配置键和数据卷不会改变。

生成包含全部基础镜像、JAR、前端、Compose、配置模板、CycloneDX SBOM、许可证、镜像 ID 锁和 SHA-256 清单的离线包：

~~~bash
deploy/scripts/build-offline-bundle.sh \
  --version 0.1.0 \
  --output-dir target/offline
~~~

打包脚本只使用本地镜像；任一基线镜像缺失都会失败，不会在打包过程中隐式拉取。输出为 `mine-platform-<version>.tar` 和对应 `.sha256`。离线包同时交付 `backup-standalone.sh`、`restore-standalone.sh`、共享校验库和对应自动化契约；它不包含任何现场备份、环境文件、secret 或私钥。备份恢复只支持同发行版本、同镜像 ID 的四个事实卷，且恢复只写不存在的新卷，不自动启动服务。

## 4. 初始化配置与 secret

~~~bash
cp deploy/env/standalone.env.example deploy/env/standalone.env
mkdir -p deploy/secrets
~~~

按 [secrets.example/README.md](secrets.example/README.md) 创建全部 secret 文件。普通密码文件只放一行随机值；Redis Exporter 使用独立 JSON 密码映射。私钥文件权限建议为 `0600`。现场证书应由矿区信任的 CA 签发，并包含浏览器访问域名和设备连接域名。

然后修改 `deploy/env/standalone.env`：

- 修改初始管理员用户名和显示名；初始密码在 secret 文件中配置。
- 需要避开现有端口时，修改 `MINE_HTTPS_PORT` 和 `MINE_MQTTS_PORT`。
- 镜像标签在发布前固定；生产离线包应额外记录镜像 digest。
- `MINE_TLS_SERVER_NAME` 必须与证书名称一致。
- 修改示例中的 `MINE_RUSTFS_ACCESS_KEY` 和 `MINE_RUSTFS_SECRET_KEY`；生产环境不得继续使用开发默认值。

环境文件和 `deploy/secrets/` 已被 Git 忽略，不得提交仓库。

将归档和外部 SHA-256 文件传到离线主机，先核对外部校验和再解包。随后在解包目录创建 `deploy/env/standalone.env` 和 `deploy/secrets/`，执行：

~~~bash
deploy/scripts/install-offline.sh \
  --bundle-dir . \
  --env-file deploy/env/standalone.env \
  --project-name mine-standalone
~~~

安装器先验证包内全部文件，再 `docker load` 并逐个核对 `manifests/images.lock` 中的镜像 ID，最后使用 `--pull never` 启动、等待健康并执行完整单机冒烟。`--load-only` 只导入和复核镜像，不启动服务。离线包不包含任何现场 secret 或私钥。

启用 `--profile observability` 时项目名必须使用 `mine-standalone`，因为当前 Alloy 和 Grafana 日志查询按该 Compose 项目标签隔离采集；核心服务的临时验收环境可以使用其他项目名。

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

三个 Java 服务只读挂载各自稳定的 `target/` 目录，启动时由 `run-java-service.sh` 校验并复制完整 JAR 到容器
`/tmp/mine-runtime/app.jar`，JVM 只运行该不可变副本。禁止把 `target/*.jar` 直接绑定为运行文件；否则容器运行期间执行
Maven `package`/`verify` 会替换可执行 JAR，造成运行时 classpath 撕裂。构建新版本后必须重建对应服务容器，不能把宿主机
构建当作热更新。

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

Standalone 默认让三维资源通过标准 S3 API 写入 RustFS，并把数据保存在 `rustfs_data` 命名卷。应用启动时仅在
`MINE_OBJECT_STORAGE_S3_INITIALIZE_BUCKET=true` 时幂等创建 bucket；该开关只适用于开发和验收环境，生产 bucket
应由基础设施流程预创建并关闭自动初始化。切换其他 S3 兼容服务时只需替换 endpoint、region、bucket 和凭据，业务代码
不依赖 RustFS SDK。`MINE_SCENE_STORAGE_ROOT` 在 S3 模式下仅作为上传校验的临时暂存目录，不保存最终资源。

`scene_upload_workspace` 由 `mine-scene-staging-init` 创建为应用 UID/GID `10001` 可写、权限 `0700`；不要改成共享临时目录。
默认上传上限为 2 GiB，解压、单文件、文件数、压缩比和 JSON 还有独立业务限制，调整前必须同时复核反向代理、Multipart、
暂存空间、对象存储和目标模型。应用就绪后会立即对场景存储执行一次有界创建/读取/删除探针，之后每 30 秒刷新指标；探针故障
只降级三维，二维位置和业务事实不加入该 readiness 门禁。

仓库确定性演示资源为 `deploy/demo/scene/demo-mine-grid.glb`。发布或验收前执行：

~~~bash
node scripts/generate-demo-scene-assets.mjs --check
bash deploy/tests/compose-config.sh
bash deploy/tests/prometheus-rules.sh
~~~

演示资源 SHA-256 固定为 `4e6e5c0c2a35bcea48a9a6679583f3bed5c1d5b225869cb27f9998e4cc611c78`；它仅用于非现场回归，
不能替代真实模型、坐标或许可证验收。管理 PostgreSQL 和 `rustfs_data` 必须作为同一恢复点备份到主机之外；只备份其中一个无法
恢复场景。禁止执行 `down -v`、清空 bucket/卷、覆盖摘要对象、直接修改已发布场景或向浏览器暴露 S3 凭据。详细发布、告警、
孤儿对象对账和安全回退见 [矿区三维场景运行与安全回退手册](../docs/runbooks/mine-scene-runtime.md)。

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

旧版 Compose 把 `timescale_data` 直接挂到 `/var/lib/postgresql/data`，当前 PostgreSQL 18 镜像要求挂到
`/var/lib/postgresql`。无论旧卷使用 PostgreSQL 17 还是曾尝试 PostgreSQL 18，都不得把旧卷直接交给新版 Compose。
计划内迁移至少执行以下步骤：

1. 停止设备写入，在旧环境执行 `pg_dump -Fc` 逻辑备份，记录备份文件 SHA-256、旧镜像 ID、扩展版本、数据库大小、hypertable 和行数基线。
2. 使用新的 Compose 项目名或独立主机创建全新的 `timescale_data`，确认 PostgreSQL 18、TimescaleDB 版本和 `timescaledb.license=apache`，禁止删除旧卷。
3. 把逻辑备份恢复到新库，重新执行 Flyway 校验；对比 hypertable、索引、最早/最晚时间、逐表行数和关键设备抽样。
4. 完成遥测写入、历史/latest 查询和备份恢复冒烟，经两人复核后切换；保留旧卷和备份直到观察期结束。

迁移窗口内不得执行 `docker compose down -v`。如果无法证明备份可恢复或新旧数据核对一致，应立即停止切换并保留旧环境。

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

不要在生产环境执行 `down -v`，它会删除 PostgreSQL、TimescaleDB、RabbitMQ、Redis 和 RustFS 数据卷。确需销毁测试环境时，必须先确认没有生产数据并完成备份。

## 7. 配置变更边界

以下配置修改后通常需要重建容器：端口、数据库连接、JVM、镜像、证书和基础设施资源限制。

gateway 以只读方式挂载整个 `deploy/config/gateway/` 目录，身份快照应先在同目录生成并校验候选文件，再原子重命名为 `device-identities.json`。从旧版“单文件 bind mount”升级时必须重建一次 `mine-gateway` 容器，使目录挂载生效；否则宿主机替换文件 inode 后，容器可能继续读取旧快照。

物模型、协议解析、字段映射、告警规则和历史数据保留策略属于平台动态策略，应在管理端完成版本化发布，不通过修改 Compose 文件实现，也不要求重启接入服务。

## 8. 生产注意事项

- 单机模式是非高可用模式。至少把数据库备份和配置导出复制到独立 NAS 或离线介质。
- RabbitMQ 同时承担设备 MQTT Broker 和平台内部消息总线，但通过虚拟主机、用户、权限和交换机隔离；不要再部署第二套 MQ。
- `mine-telemetry` 使用独立的 `mine-telemetry` 账号，只能读取平台遥测队列；Redis 与 TimescaleDB 均成功后才确认消息，任一侧故障都会触发重新投递。
- 实时链路由 `mine-telemetry` 在持久化成功后发布到 `mine.realtime.x`，每个 `mine-server` 实例创建独占自动删除队列，再推送给本实例 WebSocket 会话；多实例之间不竞争消费，也不通过定时扫描 Inbox/Outbox 表发现数据。
- 实时最新值按设备保存在 Redis Hash 中且不设置统一 TTL，避免活动设备键同时过期；历史事实以 TimescaleDB 为准。进程内一级缓存只用于后续版本化物模型、协议和策略配置，并必须采用容量上限、随机抖动 TTL、发布事件失效与 single-flight 回源。
- 单机 Redis 默认在 512 MiB 容器限制内设置 `384mb + noeviction`。达到上限时写入明确失败并让 RabbitMQ 消息背压，不能静默驱逐 boot/sequence 状态；现场容量评估后可调整 `MINE_REDIS_MAXMEMORY`，但必须为进程和 AOF 留出余量，并对 Redis 内存、RabbitMQ 积压和拒绝写入告警。
- 弱网设备应使用持久会话、QoS、消息序号和幂等处理，命令必须具有超时、回执和审计记录。
- 发布新版本前先备份，再执行冒烟测试；数据库迁移必须使用向前兼容的 expand/contract 方式。
- 当前 Compose 已包含单节点 RustFS，但仍不提供主机级容灾。五节点生产方案及完整 RPO/RTO 见 [部署与运维设计](../docs/09-deployment-operations.md)。
- Alloy 为采集容器日志只读挂载了 Docker socket；它仍具备读取 Docker 元数据和日志的高权限。必须使用固定可信镜像、限制 Grafana/Alloy 管理入口，并且不能给业务用户容器管理权限。
- 单机 Loki 使用本地文件系统并保留 30 天日志，Prometheus 默认保留 15 天指标；现场磁盘容量评估后再调整，不能把它们当作审计数据的唯一副本。
