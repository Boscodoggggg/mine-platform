# Phase 2 作用域策略与有效配置验收报告

> 验收日期：2026-07-16
>
> 验收分支：`feat/phase-0-foundation`
>
> 验收范围：设备组、产品/组/设备三级策略、在线状态运行消费、策略工作台、Gateway 失败证据与告警
>
> 当前结论：**本切片开发与非现场自动化验收通过；Phase 2 尚未整体关闭，不代表矿区生产放行**

## 1. 结论摘要

本切片已把策略从“文档中的继承规则”推进为可操作、可解释、可回滚的生产向控制面：管理员可以维护产品内设备组和历史成员关系，创建经过白名单类型校验的局部策略草稿，把版本幂等发布到产品、设备组或单设备，查询不可变历史和逐字段最终来源，并在同优先级设备组异值时获得稳定、可追踪的冲突证据。

首个进入运行消费链路的策略类型为 `ONLINE_STATUS`，当前只开放 `offlineThresholdSeconds`。设备访问状态查询会批量读取有效离线阈值；本机使用容量和 TTL 有界的缓存，控制面事务提交后精确失效受影响设备，刷新失败时对已有配置使用 last-known-good，对从未成功解析的无效配置暴露稳定错误。未发布该策略的设备继续使用接入配置阈值。

Gateway 配置分发补齐了失败证据与运维信号：永久快照错误先向控制面回报脱敏且最多 500 字符的 `FAILED` 证据，成功后才拒绝消息；证据回报失败仍重新投递。最终结果、六个处理阶段和耗时均采用有限标签指标，Prometheus 已配置永久失败严重告警和持续重试警告。

这些能力使本切片具备继续扩展其他策略类型的可靠骨架，但告警、数据质量、保留、聚合和弱网策略尚未接入运行消费者，TCP/二进制协议尚未落地。真实设备、矿区网络、双 Gateway、容量、HA、备份恢复和 24/72 小时长稳按要求未执行，因此不能把本报告作为可直接上线或销售交付的最终验收证书。

## 2. 能力验收矩阵

| 能力 | 状态 | 自动化证据与边界 |
|---|---:|---|
| 确定性三级解析器 | PASS | 覆盖产品、组、设备覆盖顺序、相同值合并、同级异值冲突、逐字段来源和稳定哈希 |
| 设备组管理 | PASS | 覆盖创建、修改、归档、乐观锁、产品隔离、当前成员差异替换和历史区间 |
| 生命周期数据库约束 | PASS | 真实 PostgreSQL 迁移验证跨产品关系、归档关闭成员、部分唯一索引、表及全字段中文注释 |
| 策略版本注册表 | PASS | 当前只接受 `ONLINE_STATUS.offlineThresholdSeconds` 整数 1～604800 秒，未知类型/字段拒绝 |
| 幂等发布与回滚 | PASS | 产品/组/设备作用范围、管理员幂等键、不可变发布序号、原因、审计和历史引用均有测试 |
| 发布前冲突门禁 | PASS | 发布、成员加入和组优先级调整均预计算受影响设备，冲突时整笔事务不提交 |
| 有效配置可解释性 | PASS | API 和工作台展示最终值、作用范围、发布来源、组优先级、哈希和冲突证据 |
| 在线状态运行消费 | PASS | 批量解析、接入配置回退、提交后失效、TTL/容量上限、last-known-good 和低基数指标已覆盖 |
| 策略工作台 | PASS | 产品/组/设备三栏、成员管理、草稿、发布、回滚、权限、中英文和主题治理已覆盖 |
| Gateway 失败证据 | PASS | 永久错误先回报 `FAILED` 再拒绝；摘要控制字符折叠、JSON/键值/Bearer 密钥脱敏和 500 字符截断 |
| Gateway 重试安全性 | PASS | 拉取、缓存、状态回报等瞬时失败保持 NACK 重投，未提前 ACK 或死信 |
| 配置分发指标与告警 | PASS | 结果、阶段、耗时指标无产品/设备/发布高基数标签；Prometheus 8 条规则通过语法校验 |
| 真实矿区与真机验收 | DEFERRED | 按当前要求未执行，不纳入通过结论 |
| 容量、HA、故障注入和长稳 | DEFERRED | 5000 MQTT、双节点、断电/磁盘、备份恢复、24/72 小时均未执行 |

## 3. 核心运行语义

1. 策略草稿先经过类型注册表校验，只有白名单字段可以持久化；发布后版本不可修改或删除。
2. 发布锁定产品和目标作用范围，计算所有受影响设备的当前片段和候选片段。任一设备出现同优先级组异值，返回 `STRATEGY-409-001`，版本发布、发布事实和审计全部回滚。
3. 产品默认先应用；设备组按优先级从低到高覆盖；单设备最后覆盖。多个同优先级组为同一字段提供相同值时安全合并，不同值时不返回部分结果。
4. 发布和回滚使用管理员作用域的 `Idempotency-Key`。回滚不修改历史记录，而是复用历史版本创建更高发布序号的新事实。
5. 成员替换采用差异更新，退出成员只关闭 `effective_to`；设备或组归档结束当前关系，历史区间保留审计语义。
6. 在线状态缓存默认最多 10000 台设备、TTL 30 秒、刷新失败退避 5 秒。控制面事务提交前不失效缓存，避免读取到未提交状态。
7. Gateway 只有在快照匹配、激活、本地 last-known-good 持久化、节点 `LOADED` 回报全部成功后才 ACK。永久快照错误只有在 `FAILED` 证据成功保存后才拒绝；证据保存失败继续重投。

## 4. 自动化验收结果

| 验收项 | 命令 | 结果 |
|---|---|---:|
| CI 同款综合门禁 | `bash scripts/verify.sh` | PASS；后端、前端、部署配置、SBOM、许可证与离线包全部通过 |
| 全仓 Maven 验证 | `./mvnw -B -ntp verify` | PASS；10 个 Reactor 模块全部成功 |
| Server 单元与架构测试 | Maven `verify` 的 Surefire 阶段 | PASS；229 项 |
| Server PostgreSQL/Redis 集成测试 | Maven `verify` 的 Failsafe 阶段 | PASS；76 项 |
| Gateway 单元测试 | Maven `verify` 的 Surefire 阶段 | PASS；58 项 |
| Gateway RabbitMQ 集成测试 | Maven `verify` 的 Failsafe 阶段 | PASS；3 项 |
| Flyway 与数据库注释 | `DatabaseMigrationIT` | PASS；空库执行 22 个迁移，11 项断言通过 |
| 策略与失败证据定向测试 | `./mvnw -B -ntp -pl mine-gateway,mine-server -am -Dtest=... test` | PASS；Gateway 15 项、Server 12 项 |
| UI 治理 | `pnpm --dir mine-web check:ui-governance` | PASS；43 项 |
| 前端类型与静态检查 | `pnpm --dir mine-web typecheck`、`lint:check` | PASS；0 warning、0 error |
| 前端单元测试 | `pnpm --dir mine-web test` | PASS；26 个文件、154 项 |
| 前端生产构建 | `pnpm --dir mine-web build` | PASS |
| Chromium E2E | `pnpm --dir mine-web test:e2e --project=chromium` | PASS；11/11，其中策略成功/冲突流程 2/2 |
| Compose 静态回归 | `bash deploy/tests/compose-config.sh` | PASS |
| Prometheus 规则 | `promtool check config` | PASS；配置有效，8 条规则 |
| Loki 与 Alloy | `scripts/verify.sh` 中的配置校验 | PASS |
| SBOM 与许可证 | `bash scripts/generate-sbom.sh` | PASS；前后端 CycloneDX 1.6，无 AGPL、SSPL、Commons Clause |
| 离线包结构 | `bash deploy/tests/offline-bundle.sh` | PASS；内容、secret 排除、校验和、镜像 ID 和安装流程均通过 |

测试日志中出现的缓存磁盘失败、Broker confirm 失败、数据库失败和连接重置均来自故障分支用例，最终断言和 Maven 结果为通过。Chromium 开发服务器记录一次 `ResizeObserver loop completed` 浏览器警告，11 项用例仍全部通过；该警告不作为失败，但应在后续前端性能与可访问性专项中继续观察。

## 5. 数据库与升级边界

本切片新增 V20～V22：V20 创建设备组、历史成员、策略版本、策略发布、触发器、索引、权限和完整中文注释；V21 允许安全关闭已归档关系，并在设备归档时自动结束全部当前成员关系；V22 将既有隐藏策略权限节点原位升级为可见工作台，保留已有角色授权关系。

升级采用只向前 Flyway migration。策略版本和发布事实由数据库触发器保护为不可变；回滚通过新发布完成，不执行表数据回退。应用回滚到不认识 V20～V22 的旧版本前必须先验证旧代码能够容忍新表和菜单，不应删除迁移或手工回写 schema history。

## 6. 证据边界与延期项

本次 Testcontainers 使用真实 PostgreSQL、Redis 和 RabbitMQ 镜像，证明仓库契约与基础组件集成；Playwright 使用确定性的 API 模拟，证明浏览器交互、权限和请求契约。它们都不等价于矿区生产环境。

以下项目明确标记 `DEFERRED`：

- 厂商真实设备、真实报文、矿区网络和 24 小时连续运行；
- 双 Gateway 离线追赶、滚动发布、一致性和主机断电恢复；
- 5000 MQTT 客户端、UWB 峰值、重连风暴和 RabbitMQ 积压恢复；
- 数据库主备、Redis 故障、备份恢复、磁盘写满和 72 小时稳定性；
- 告警送达、值班响应、外部密钥托管和生产服务令牌轮换演练；
- 安全渗透、漏洞处置 SLA、灾备 RTO/RPO 和正式上线签字。

## 7. Phase 2 剩余工作与建议顺序

1. 实现数据质量策略的类型注册表、Telemetry 运行消费者、质量证据和告警，复用本次三级有效值骨架。
2. 实现告警策略与告警事件确认/恢复闭环，这是进入智慧矿山业务层前最重要的运营能力。
3. 实现保留、聚合和弱网策略的受控任务/运行消费者，不允许只增加前端表单而没有实际生效点。
4. 设计 TCP/二进制协议 SPI，交付首个长度字段、字节序、CRC 和粘拆包纵向实现及示例插件。
5. 完成上述非现场研发后，再按独立计划执行真机、容量、HA 和长稳验收，关闭 Phase 1/2/4 的生产放行阻塞项。

## 8. 关联资料

- [物模型、字段映射与策略中心](../04-device-model-strategy.md)
- [API、消息与数据库契约](../11-contracts-and-database.md)
- [开发路线与 Codex 工作流](../12-roadmap-and-codex-workflow.md)
- [Phase 2 协议配置发布闭环验收报告](phase-2-protocol-release-acceptance.md)
- [测试体系与上线质量门禁](../10-testing-quality-gates.md)
