# 单机一体化部署

本目录提供一期可执行的单矿区单机部署基线。它用于开发联调、现场验收和不要求主机级高可用的生产环境；一台主机故障会导致整个平台中断，因此生产数据必须备份到主机之外。

## 1. 当前包含的服务

| 服务 | 作用 | 是否发布到宿主机 |
|---|---|---|
| `nginx` | 管理端静态资源、HTTPS 与 API 反向代理 | `443`，可配置 |
| `rabbitmq` | 平台内部 AMQP、管理页面与设备 MQTTS 接入 | `5672`、`15672`、`8883`，可配置 |
| `mine-server` | 系统管理和矿山业务 API | 否 |
| `mine-gateway` | 设备 TCP Binary 接入和协议处理 | `9000`，可配置 |
| `mine-telemetry` | 遥测消费、处理和入库 | 否 |
| `postgres` | 业务数据 | `5432`，可配置 |
| `timescale` | 遥测时序数据 | 宿主 `5433` → 容器 `5432`，可配置 |
| `redis` | 会话、缓存和实时状态 | `6379`，可配置 |
| `rustfs` | S3 兼容对象存储与管理控制台 | 控制台 `9001`，可配置；S3 API 仅容器内 `9000` |

启用 `observability` profile 后还会启动 Prometheus、Grafana、Loki、Alloy 以及 PostgreSQL/TimescaleDB exporter。Grafana `3000`、Prometheus `9090` 和 Loki `3100` 默认都只绑定宿主机 `127.0.0.1`。需要让运维工作站直接访问 Grafana 时，把 `MINE_GRAFANA_BIND_ADDRESS` 改为指定管理网 IP，并由现场防火墙限制来源；不要把它开放到设备网。

Standalone 为方便矿区内网开发、验收和现场排障，常驻发布数据库、Redis、RabbitMQ、Gateway TCP 和 RustFS
控制台端口，默认监听宿主全部网卡。该基线只适用于没有公网 IP、没有公网端口转发且受现场防火墙保护的矿区内网；
防火墙必须按办公网、运维网和设备网来源限制访问，禁止把这些端口直接发布到互联网。Java 应用端口、Actuator、RustFS
S3 API、Exporter 和 Alloy 管理端口仍只存在于 Compose 隔离网络。

## 2. 前置条件

- Linux x86_64/ARM64 主机，推荐至少 8 核、16 GiB 内存和 200 GiB 可用磁盘；最终容量应按设备数、上报频率和保留周期重新核算。
- Docker Engine 24+，Docker Compose v2。
- 联网构建机需要 JDK 21、Node.js 20+、pnpm、jq、Docker Engine 24+ 和 Docker Compose v2，用于编译制品、生成并校验 SBOM、归档本地镜像。
- 现场离线机需要 Bash、Docker Engine 24+、Docker Compose v2、curl、OpenSSL、jq，以及常见 Linux 基础命令：`awk`、`df`、`grep`、`sort`、`stat`、`seq` 和 `sha256sum`（也兼容 `shasum`）。安装器会在导入镜像前统一检查。
- 已确认 `443`、`5432`、`5433`、`5672`、`6379`、`8883`、`9000`、`9001`、`15672` 未被占用，系统时间同步正常。

生产环境不要直接使用示例密码或临时自签名证书。

## 3. 构建制品

在项目根目录执行：

~~~bash
bash scripts/verify.sh
~~~

也可以分别执行标准构建命令：

~~~bash
./mvnw -B -ntp package
pnpm --dir mine-web build
~~~

构建完成后会自动得到 `release/mine-server.jar`、`release/mine-gateway.jar`、`release/mine-telemetry.jar` 和
`release/mine-web.zip`，不需要再运行单独的打包脚本。`release/` 是对外拿取构建产物的位置；Maven `target/` 与
`mine-web/dist/` 仍是构建过程目录。

部署始终使用通用 Java 21 JRE 和 Nginx 镜像：三个 Java 服务分别只读挂载 `deploy/backend/*.jar`，Nginx 只读挂载
`deploy/frontend/dist`。业务制品不封装进应用镜像，升级时替换挂载制品并重建对应容器。

需要在本机直接启动 Compose 时，再把 Maven 和前端构建结果安装到 `deploy/`：

~~~bash
deploy/scripts/prepare-artifacts.sh
~~~

该脚本只负责准备运行挂载目录，不参与生成 `release/` 发行包。

生成包含全部基础镜像、JAR、前端、Compose、配置模板、CycloneDX SBOM、许可证、镜像 ID 锁和 SHA-256 清单的离线包：

~~~bash
deploy/scripts/build-offline-bundle.sh \
  --version 0.1.0 \
  --output-dir target/offline
~~~

打包脚本只使用本地镜像；任一基线镜像缺失都会失败，不会在打包过程中隐式拉取。输出为 `mine-platform-<version>.tar` 和对应 `.sha256`。离线包中的 `deploy/` 已包含 Compose、JAR、前端、静态配置和运维脚本；它不包含现场 `.env`、TLS 私钥或现场备份。备份恢复只支持同发行版本、同镜像 ID 的四个事实卷，且恢复只写不存在的新卷，不自动启动服务。

## 4. 初始化配置与证书

~~~bash
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
mkdir -p deploy/certs
~~~

所有账号、密码、数据加密密钥、镜像、端口、JVM 参数、制品路径和业务开关都在 `deploy/.env` 配置。真实文件不得提交 Git，必须设置为 `0600`；Compose 只向每个容器注入该服务实际需要的变量。该文件同时由 Bash 运维脚本加载，包含空格、`#`、`$` 或引号的值必须使用兼容 Bash 的单引号写法。环境变量会被具有 Docker 管理权限的人员通过容器元数据读取，因此 Docker 主机管理权限必须严格限制。

修改 `deploy/.env`：

- 替换所有 `change-me-*` 密码、令牌和数据密钥占位值。
- 修改初始管理员用户名、显示名和初始密码。
- 需要避开现有端口时，修改对应的 `MINE_*_PORT`；业务 PostgreSQL 与 TimescaleDB 使用不同宿主端口。
- 镜像标签在发布前固定；生产离线包应额外记录镜像 digest。
- `MINE_TLS_SERVER_NAME` 必须与证书名称一致。
- 修改示例中的 `MINE_RUSTFS_ACCESS_KEY` 和 `MINE_RUSTFS_SECRET_KEY`；生产环境不得继续使用开发默认值。

默认内网连接入口如下；主机地址以现场服务器 IP 为准：

| 工具或协议 | 默认入口 | 凭据来源 |
|---|---|---|
| DBeaver / PostgreSQL | `服务器IP:5432` | `POSTGRES_USER` / `POSTGRES_PASSWORD` |
| DBeaver / TimescaleDB | `服务器IP:5433` | `TIMESCALE_READER_USER` / `TIMESCALE_READER_PASSWORD` |
| RedisInsight / redis-cli | `服务器IP:6379` | `REDIS_PASSWORD` |
| RabbitMQ AMQP | `服务器IP:5672` | 对应最小权限 RabbitMQ 账号 |
| RabbitMQ 管理页面 | `http://服务器IP:15672` | `RABBITMQ_ADMIN_USERNAME` / `RABBITMQ_ADMIN_PASSWORD` |
| RustFS 管理控制台 | `http://服务器IP:9001` | `MINE_RUSTFS_ACCESS_KEY` / `MINE_RUSTFS_SECRET_KEY` |

`5432`、`5433`、`5672`、`6379`、`15672` 和 `9001` 当前是带认证的内网明文连接，不得穿越不可信网络。
设备 MQTTS `8883` 使用 TLS；Gateway TCP `9000` 是否启用 TLS 由 `MINE_GATEWAY_TCP_TLS_ENABLED` 控制。
从旧版配置升级时，如果现有 `deploy/.env` 仍包含 `MINE_GATEWAY_TCP_PUBLISHED_ADDRESS='127.0.0.1'`，需要按本次
内网常驻发布方案改为 `0.0.0.0`；缺少其余新增端口变量时 Compose 会使用表中的默认端口。

把现场证书链保存为 `deploy/certs/tls.crt`，私钥保存为 `deploy/certs/tls.key` 并执行 `chmod 600 deploy/certs/tls.key`。证书应由矿区信任的 CA 签发，并同时包含浏览器访问域名和设备连接域名。Nginx 以只读方式直接挂载该目录；Gateway 与 RabbitMQ 的初始化任务以 root 读取宿主私钥，再复制到各自专属命名卷并按服务 UID 收敛为 `0400`，业务容器只读挂载，禁止为了兼容非 root 服务把宿主私钥放宽为全局可读。真实 `.env`、证书、私钥和部署制品均被 Git 忽略。

从旧版 `deploy/env/standalone.env` 与 `deploy/secrets/` 升级时执行：

~~~bash
deploy/scripts/migrate-legacy-env.sh
~~~

脚本生成权限为 `0600` 的 `deploy/.env`、迁移 TLS 文件但保留旧目录；若检测到历史公开 RustFS 开发凭据，会生成新的随机凭据且不会输出明文。目标文件已存在时必须显式使用 `--force`，覆盖前应先备份并确认当前容器使用的配置。

将归档和外部 SHA-256 文件传到离线主机，先核对外部校验和再解包。随后在解包目录创建 `deploy/.env` 和 `deploy/certs/`，执行：

~~~bash
deploy/scripts/install-offline.sh \
  --bundle-dir . \
  --env-file deploy/.env \
  --project-name mine-standalone
~~~

安装器先验证包内全部文件，再 `docker load` 并逐个核对 `manifests/images.lock` 中的镜像 ID，最后使用 `--pull never` 启动、等待健康并执行完整单机冒烟。`--load-only` 只导入和复核镜像，不启动服务。离线包不包含任何现场 `.env`、证书或私钥。

启用 `--profile observability` 时项目名必须使用 `mine-standalone`，因为当前 Alloy 和 Grafana 日志查询按该 Compose 项目标签隔离采集；核心服务的临时验收环境可以使用其他项目名。

## 5. 启动与验收

直接启动核心服务：

~~~bash
deploy/scripts/start.sh
~~~

启用生产可观测组件：

~~~bash
MINE_DEPLOY_PROFILE=observability deploy/scripts/start.sh
~~~

`deploy/.env.example` 默认不启用 profile；也可以在现场 `.env` 中持久配置 `MINE_DEPLOY_PROFILE=observability`。调用命令前显式设置的 `MINE_DEPLOY_PROFILE` 优先，可用空值临时只启动核心服务。

启动脚本会先检查 `.env` 权限、占位密码、旧版公开开发凭据、JAR、前端、证书、磁盘、文件句柄和 Compose。三个 Java 服务挂载独立发行 JAR，`run-java-service.sh` 校验后复制到容器临时目录再运行；不要直接把 Maven `target/` 当作部署目录，也不要在容器运行期间覆盖 `deploy/backend/*.jar`。更新制品或轮换证书后使用 `deploy/scripts/restart.sh` 强制重建容器和 TLS 暂存任务。

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
docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml ps
docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml logs --tail=200 mine-server
~~~

TimescaleDB 基线固定为 PostgreSQL 18.4 + TimescaleDB 2.28.2 OSS，并在容器健康检查和冒烟测试中要求
`timescaledb.license=apache`。已经从旧示例复制过 `deploy/.env` 的开发环境，需要同步更新
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
MINE_ENV_FILE=deploy/.env bash deploy/tests/observability-smoke.sh
~~~

该脚本验证三个 Java 服务、RabbitMQ、PostgreSQL 和 TimescaleDB 的 Prometheus target 均为 `up`，Grafana/Loki 可用，并生成一次 HTTPS 请求后按 `X-Request-Id` 在 Loki 中反查日志。Grafana 管理密码来自 `deploy/.env` 的 `MINE_GRAFANA_ADMIN_PASSWORD`，总览仪表板和 Prometheus/Loki 数据源会自动配置。

## 6. 停止与清理

正常停止但保留数据卷：

~~~bash
docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml down
~~~

不要在生产环境执行 `down -v`，它会删除 PostgreSQL、TimescaleDB、RabbitMQ、Redis 和 RustFS 数据卷。确需销毁测试环境时，必须先确认没有生产数据并完成备份。

### 6.1 同版本冷备份与新卷恢复

离线包中的 `deploy/scripts/backup-standalone.sh` 只允许在全部 Compose 服务停止后归档四个事实卷；
`deploy/scripts/restore-standalone.sh` 会先验证外部/内部 SHA-256、manifest、backupId、发行版本、镜像引用/ID 和全部目标卷，
再恢复到新项目的四个不存在卷。两者都不会自动停止、启动或删除服务和卷，也不包含 `.env`、证书或 TLS 私钥。

完整的停写审批、命令、异地保留、分阶段启动、数据对账和失败卷人工清理边界见
[Standalone 同版本冷备份与恢复运行手册](../docs/runbooks/standalone-backup-restore.md)。禁止在线归档、跳过校验、覆盖已有卷或执行
`down -v`；该冷备底座不替代 WAL/PITR、RPO/RTO 和真实恢复演练。

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
