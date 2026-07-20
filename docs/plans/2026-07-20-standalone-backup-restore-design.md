# Phase 4 单机同版本备份恢复设计

## 1. 目标与产品边界

Phase 4 的第一个切片交付单机 Compose 的同版本冷备份与安全恢复闭环。它解决的是主机损坏、磁盘更换或升级前创建可校验恢复点的问题，而不是把“复制了几个 Docker 卷”包装成已经完成生产容灾。

本切片覆盖四个事实卷：管理 PostgreSQL、TimescaleDB、RabbitMQ 和 RustFS。管理库保存用户、权限、设备、配置、矿山业务、审计和可靠消息状态；TimescaleDB 保存遥测、位置、空间事件和运行事实；RabbitMQ 冷快照保存定义、权限和停机时尚未消费的持久消息；RustFS 保存三维内容对象。四者必须来自同一个完全停写窗口，缺少任意一个都不能形成可恢复集。

Redis、Prometheus、Grafana、Loki、Alloy、上传暂存、Gateway 最后正确缓存不属于本恢复集的权威事实：它们可以从数据库、消息或新流量重建。现场环境文件、TLS 私钥和 secret 不进入数据备份，必须由独立的加密配置托管流程恢复。

本切片明确不包含在线热备、WAL/PITR、跨 PostgreSQL major 恢复、五节点 HA、云厂商快照、内置加密算法或真实矿区恢复演练。它形成后续升级编排和现场备份恢复验收的安全底座，但自动化通过仍是 `PRODUCTION NOT RELEASED`。

## 2. 方案选择

考虑过三种路线：

1. 热逻辑备份：对 PostgreSQL/TimescaleDB 使用 `pg_dump`，RabbitMQ 导出定义，对象存储单独同步。它适合长期演进和跨补丁恢复，但无法在当前产品内自然提供管理库、时序库、队列在途消息和对象存储的统一业务时点，也需要引入 S3 清单/同步工具。
2. 同版本冷卷恢复集：维护窗口内停止全部 Compose 服务，只读归档四个本地命名卷，记录发行版本、Compose 摘要、镜像引用/ID、卷驱动和文件校验和。恢复到不存在的新卷，不覆盖旧现场。该方案最适合当前 Standalone，边界清晰、离线可执行、能够为升级前回退提供完整恢复点。
3. 宿主机/LVM/ZFS 快照：速度快且可保持卷组一致，但强绑定现场存储技术，无法成为开源仓库的通用交付能力。

首版选择方案 2。方案 1 将作为后续“持续备份与跨版本升级”切片，方案 3 留给具体交付架构。冷备并不满足当前文档中的持续 WAL 和 RPO 目标；它只提供显式维护窗口的同版本灾难恢复点。

## 3. 备份流程

新增 `deploy/scripts/backup-standalone.sh`。调用方必须提供现场环境文件、Compose 项目名、发行版本、输出目录，并通过 `--confirm-secure-destination` 明确确认输出位于受控加密介质或加密文件系统。脚本本身不发明密钥管理或自定义加密格式。

脚本按以下顺序执行：

1. 校验项目名、版本、输出目录、依赖命令和 Compose 配置。
2. 查询 Compose 全部运行中服务；只要存在任何运行服务就拒绝备份。脚本不会自行停止服务，避免未经授权扩大停机范围。
3. 解析 `postgres`、`timescale`、`rabbitmq`、`rustfs` 的镜像引用并读取本地不可变 image ID。
4. 确认 `${project}_postgres_data`、`${project}_timescale_data`、`${project}_rabbitmq_data`、`${project}_rustfs_data` 全部存在且使用 `local` 驱动。
5. 使用已经进入离线包的 PostgreSQL Alpine 镜像作为只读、`--network none` 的归档助手，逐卷生成 tar；源卷只读挂载，输出目录单独挂载。
6. 写入 schema v1 `manifest.json`，记录 backupId、UTC 时间、发行版本、源项目、Compose SHA-256、四项服务镜像引用/ID、四个逻辑卷和实际卷名/驱动。
7. 对 manifest 和四个卷归档生成内部 `checksums.sha256`，再生成单根目录 tar 和外部 SHA-256 文件。

备份中途失败不会删除已经存在的输出证据；临时工作目录自动清理，最终归档只通过原子重命名发布。归档包含数据库、RabbitMQ 凭据状态和业务数据，属于高敏数据，禁止提交 Git、放入 Web 目录或未经加密传输。

## 4. 恢复流程

新增 `deploy/scripts/restore-standalone.sh`。恢复必须提供备份 tar、现场环境文件、目标 Compose 项目名，并用 `--confirm-backup-id` 精确输入 manifest 中的 backupId。发行版本默认从离线包根 `VERSION` 读取；开发仓库必须显式提供 `--expected-release-version`。

恢复严格先验证、后写入：

1. 校验外部 SHA-256；检查 tar 条目只能位于唯一备份根目录，拒绝绝对路径和 `..`。
2. 解包到临时目录，验证内部 checksum、manifest schema、backupId、四个逻辑卷集合和每个归档均非空。
3. 要求 manifest 发行版本与目标离线包完全一致；解析目标 Compose 四项镜像，要求引用和本地 image ID 与备份一致。该门禁明确阻止跨 PostgreSQL/TimescaleDB/RabbitMQ/RustFS 版本碰运气恢复。
4. 确认目标 Compose 没有运行服务，四个目标卷均不存在。脚本禁止覆盖或清空任何现有卷；旧故障卷必须由运维另行保留、重命名方案记录或经审批移除。
5. 创建四个 `local` 目标卷，使用匹配的归档助手以 `--network none` 解包并保留文件模式和数字所有者。
6. 写出恢复摘要并停止，不自动启动 Compose、不执行 Flyway、不删除备份或旧卷。

任何创建卷后的失败都保留已创建卷并打印名称，禁止自动回滚删除。运维必须检查原因后人工处理。恢复成功后使用匹配离线包和独立 secret/config 启动，依次验证数据库版本、Timescale 扩展、RabbitMQ、RustFS、Flyway、业务 API、场景摘要和实时链路。

## 5. 安全与失败模型

脚本遵循“拒绝不确定性”原则：运行中服务、缺卷、非本地驱动、镜像漂移、版本不符、校验和错误、归档路径穿越、备份 ID 不匹配、目标卷已存在都会在写入前失败。用户输入必须通过有限正则校验，Docker 命令使用数组，禁止 `eval` 和拼接执行。

备份只读挂载源卷，归档助手关闭网络；恢复只写新卷。脚本不读取、复制或打印现场 secret，也不把解析后的 Compose 环境写入 manifest。manifest 只记录非敏感的版本、镜像、摘要和卷身份。输出目录必须由运维预先放在加密介质上，本切片不使用口令参数，避免口令进入 shell 历史或进程列表。

冷卷备份只在所有服务完全停止后具有一致性。仅停止数据库而让应用、RabbitMQ 或 RustFS 继续运行不被接受。RabbitMQ 冷快照是恢复集的一部分，但不能替代长期消息归档；Redis 和监控卷不恢复是有意设计，不得在文档中误报为数据丢失。

物理卷只承诺同发行版本、同镜像 ID 和同架构恢复。数据库 major 升级必须走逻辑备份/恢复或官方升级路径，不能通过关闭版本门禁强制加载旧数据目录。

## 6. 自动化测试与验收

新增 `deploy/tests/standalone-backup-restore.sh`，使用真实 tar/jq/sha256 和受控 fake Docker 验证脚本行为，不修改开发机现有卷：

- 服务运行时备份拒绝，且不调用归档容器。
- 缺卷、非 local 驱动、未确认安全目的地、非法项目/版本均拒绝。
- 成功备份只包含四个事实卷、manifest 和 checksum，不包含 env、secret、Redis、监控或暂存卷。
- 归档容器必须源卷只读、网络关闭；最终归档和外部 checksum 可复验。
- 恢复在外部/内部 checksum 错误、路径穿越、版本/镜像漂移、确认 ID 错误、归档缺项、目标服务运行或目标卷存在时均在创建卷前拒绝。
- 成功恢复只创建四个 local 卷，以网络关闭的助手恢复，不启动 Compose、不删除任何卷。

`scripts/verify.sh` 纳入该测试；离线包测试确认两个脚本随包发布。运行手册记录停机、备份、异地复制、隔离恢复、启动对账和禁止事项。最终验收报告只能写 `AUTOMATED PASS`，真实备份介质、恢复时长、RPO/RTO、跨主机权限、磁盘故障和季度演练全部保持 `DEFERRED`。
