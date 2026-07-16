# Phase 2 协议配置发布闭环验收报告

> 验收日期：2026-07-16
>
> 验收分支：`feat/phase-0-foundation`
>
> 验收范围：MQTT JSON 协议包、黄金样本、发布门禁、配置分发、Gateway 热加载、回滚与重启恢复
>
> 当前结论：**Phase 2 开发与自动化验收通过；不代表真实矿区生产放行**

## 1. 结论摘要

Phase 2 已形成一条可审计的协议配置纵向闭环：维护协议包和黄金样本，服务端重新执行样本门禁，创建不可变发布记录并通过 Outbox 分发，Gateway 校验快照、编译并热切换运行时配置，持久化最后已知良好版本后回报节点加载状态，管理端展示发布和节点证据，并允许通过创建新发布回滚到历史配置。

三类仓库内示例——平铺字段、嵌套字段、枚举与缩放——已由真实协议解析内核执行。纵向测试证明发布后无需重启即可切换解析规则，损坏快照不会替换最后已知良好配置，回滚发布能够恢复旧规则。Gateway 的本地快照缓存使用有界文件、同目录临时文件、文件同步和原子替换；单机 Compose 为缓存提供独立持久卷。

本报告的 `PASS` 仅覆盖 MQTT JSON 配置发布开发基线和自动化证据。TCP、厂商二进制协议、真实设备现场报文、跨节点长稳、断电恢复和生产级密钥管理不在本次通过结论内；Phase 1 报告中的真实设备 24 小时现场验收仍保持阻塞。

## 2. 能力验收矩阵

| 能力 | 状态 | 自动化证据 |
|---|---:|---|
| MQTT JSON 协议编译与执行 | PASS | 协议引擎单元测试覆盖字段映射、嵌套路径、类型转换、枚举、缩放和错误输入 |
| 黄金样本回归门禁 | PASS | 服务端发布服务测试和浏览器失败流证明样本未全通过时拒绝发布 |
| 不可变发布与幂等 | PASS | 配置服务、控制器、数据库和 Outbox 测试覆盖发布序号、幂等键及重复请求 |
| 配置事件可靠分发 | PASS | Outbox 集成测试与 Rabbit 发布器测试覆盖事务落库和事件发布 |
| Gateway 快照校验与热加载 | PASS | 消费者测试覆盖摘要校验、版本单调保护、加载回报、ACK 与 NACK |
| 最后已知良好版本恢复 | PASS | 缓存与消费者测试覆盖写入、恢复、幂等、序号保护、损坏文件和磁盘失败；Compose 配置测试覆盖持久卷 |
| 无重启发布与回滚 | PASS | `ProtocolReleaseVerticalTest` 依次切换三类配置、拒绝损坏版本并回滚到旧规则 |
| 管理端发布工作台 | PASS | Vitest、UI 治理和 Playwright 覆盖样本、门禁、发布、节点状态与回滚流程 |
| 中英文与主题治理 | PASS | UI 治理、类型检查、Lint、单元测试和生产构建全部通过 |
| 真实设备现场运行 | NOT RUN | 缺少厂商设备、现场网络、真实报文和连续观察窗口，不纳入本次开发验收 |

## 3. 发布运行语义

1. 服务端以协议包当前内容重新执行全部黄金样本，只有全部通过才允许创建发布。
2. 发布记录、目标快照摘要和 Outbox 事件在同一事务边界内落库；回滚不会修改历史记录，而是创建一个指向历史配置的新发布。
3. Gateway 收到 `config.release.created` 后拉取内部快照，校验发布标识、产品、序号和 SHA-256，再编译并激活协议执行计划。
4. 激活后的快照必须成功原子持久化，节点才回报 `LOADED` 并 ACK；持久化失败会 NACK 并重入队列，平台不会把该节点误判为已完成加载。
5. Gateway 启动时先从持久缓存恢复最后已知良好快照，再继续接收新发布，因此平台短时不可达不会使已发布协议失效。
6. 同一产品在同一时刻只有一个活动协议快照。报文结构不兼容的设备应拆分到不同产品；这也是当前协议兼容边界。

## 4. 自动化验收结果

| 验收项 | 命令 | 结果 |
|---|---|---:|
| 全仓 Maven 验证 | `./mvnw -B -ntp verify` | PASS，10 个 Reactor 模块全部成功；`mine-server` Failsafe 71 项集成测试通过 |
| Phase 2 Gateway 定向验证 | `./mvnw -pl mine-gateway -am -Dtest=ConfigurationSnapshotCacheTest,ConfigurationReleaseConsumerTest,GatewayRabbitConfigurationTest,ProtocolReleaseVerticalTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS，13 项测试通过 |
| UI 治理 | `pnpm --dir mine-web check:ui-governance` | PASS，32 项治理测试通过 |
| 前端类型检查 | `pnpm --dir mine-web typecheck` | PASS |
| 前端静态检查 | `pnpm --dir mine-web lint:check` | PASS，0 warning、0 error |
| 前端单元测试 | `pnpm --dir mine-web test` | PASS，23 个文件、125 项测试通过 |
| 前端生产构建 | `pnpm --dir mine-web build` | PASS |
| Chromium E2E | `pnpm --dir mine-web exec playwright test --project=chromium` | PASS，9/9；其中协议发布成功与门禁失败流程 2/2 |
| Compose 配置回归 | `bash deploy/tests/compose-config.sh` | PASS，缓存环境变量、持久卷和初始化依赖均通过 |

全量浏览器回归曾在与 Maven 容器集成测试并发执行时出现资源竞争超时；改为单独执行后 9/9 通过。旧的运行监控 E2E 夹具同时按当前历史概览接口完成了对齐，没有修改运行监控业务代码。

## 5. 证据边界

本次浏览器 E2E 使用确定性的 API 模拟，证明的是前端交互、权限与请求契约，不把它描述为真实分布式环境发布。`ProtocolReleaseVerticalTest` 使用真实协议编译器和运行时注册表执行仓库示例，证明热切换、损坏版本保护和回滚语义；配置服务、Outbox、RabbitMQ、数据库迁移和 Gateway 消费者则分别由单元及 Testcontainers 集成测试验证。

这些证据组合能够支撑 Phase 2 代码基线验收，但不能替代以下现场证据：

- 厂商真实 MQTT 报文和异常报文回放；
- 至少两个 Gateway 节点的滚动发布、一致性和离线追赶；
- 主机断电、磁盘写满、缓存文件损坏后的恢复演练；
- 真实弱网、重连风暴、队列积压和 24/72 小时长稳；
- 生产服务令牌轮换、最小权限、外部密钥托管和审计联动。

## 6. 当前范围与下一阶段建议

当前协议内核仅承诺 MQTT JSON。TCP 粘拆包、二进制帧、校验码、字节序、位域和厂商私有协议需要独立执行模型，不能通过继续堆叠 JSON 映射规则实现。

建议下一阶段按以下顺序推进：

1. 选择一个真实矿卡或环境传感器型号，脱敏固化厂商正常、边界和异常报文集。
2. 在双 Gateway 环境执行发布、节点离线追赶、回滚、断电和磁盘故障演练。
3. 将缓存恢复状态、当前发布标识和加载失败原因接入 Prometheus 告警与运维页面。
4. 完成 TCP/二进制协议 SPI 设计，再选择首个厂商协议做纵向实现，避免过早建设通用低代码协议编辑器。
5. 与 Phase 1 现场手册合并执行真实设备 24 小时验收，关闭生产放行阻塞项。

## 7. 关联资料

- [设备接入与协议边界](../03-device-access-protocol.md)
- [物模型与配置发布策略](../04-device-model-strategy.md)
- [接口、事件与数据库契约](../11-contracts-and-database.md)
- [Phase 1 真实设备验收报告](phase-1-acceptance.md)
- [真实设备现场接入与 24 小时验收手册](../runbooks/device-onboarding.md)
