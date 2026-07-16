# 系统可观测性控制台设计

## 1. 状态与目标

状态：已确认

本设计将“系统管理 / 运行监控”从按需健康检查页升级为面向现场运维人员的可观测性控制台。页面需要回答四个问题：平台现在是否可用、异常从何时开始、性能是否持续恶化、下一步应检查哪里。

平台已经提供 Prometheus、Grafana、Loki、Alloy、PostgreSQL exporter、TimescaleDB exporter、应用 Actuator 指标和基础告警规则。历史监控不得另建业务表或新增 `mine-observer` 服务，避免形成与 Prometheus 口径不一致的第二套时序数据。

现有 `SystemOperationsService` 保留为管理员主动触发的即时深度探测。Prometheus 负责周期采集、历史保存和聚合查询，两者职责不得混用：

- Prometheus 回答“过去一段时间发生了什么”；
- 即时深度探测回答“此刻从管理服务视角能否连通依赖”；
- Grafana 继续承担专家级全量指标和日志分析；
- 管理端控制台只展示经过筛选的日常运维结论。

## 2. 架构决策

### 2.1 总体结构

```text
Java Actuator / RabbitMQ / 数据库与 Redis exporter
                         |
                         v
                 Prometheus 历史指标
                         |
                         v
mine-web -> mine-server 固定查询接口 -> Prometheus HTTP API
    |
    `------> 现有 SystemOperationsService 即时深度探测
```

浏览器不得直接访问 Prometheus。`mine-server` 使用只读内部地址查询 Prometheus，并将固定 PromQL 模板的结果转换为稳定业务契约。接口不接受任意 PromQL，防止越权探测、超大范围查询和高基数查询拖垮 Prometheus。

不新增独立 Java 监控服务，原因如下：

1. 单矿区单机部署已经包含成熟的可观测组件，新增服务只会重复采集和存储。
2. Prometheus 位于业务进程之外，业务服务宕机后仍能记录目标不可用状态。
3. 当前工程处于底座建设阶段，新增部署单元会扩大离线打包、密钥、网络、健康检查和升级面。
4. Grafana 和 Loki 已经提供深入排障入口，管理端无需复制完整专家工具。

### 2.2 启动独立性

Prometheus 当前等待业务服务、RabbitMQ 和 exporter 健康后才启动，这会导致平台启动故障时失去观测能力。可观测组件必须先于或独立于被观测目标启动：

- Prometheus 不依赖业务目标健康；目标未就绪应表现为 `up = 0`。
- exporter 不等待数据库健康，连接失败时持续运行并暴露采集失败状态。
- Grafana 可以等待 Prometheus 和 Loki 自身就绪，但不能等待业务目标健康。
- `observability` profile 未启用时，管理端明确降级为“即时检查模式”。

### 2.3 数据保留

沿用 Prometheus 本地 TSDB，不在 PostgreSQL 或 TimescaleDB 创建监控历史表。默认保留周期先维持 15 天，并通过部署环境变量允许现场按容量调整。管理端提供 `1 小时`、`6 小时`、`24 小时`、`7 天` 和 `15 天`范围；请求超过实际保留周期时，后端返回可用范围，前端不得把缺失历史显示为正常。

若现场明确要求 30 天以上历史，应先完成 Prometheus 磁盘容量评估，再调整保留周期；本期不引入长期存储组件。

## 3. 指标范围与口径

### 3.1 首期监控目标

| 检查项 | 历史数据来源 | 即时检查来源 |
|---|---|---|
| `mine-server` | Prometheus 抓取状态和 Actuator 指标 | 当前进程探测 |
| `mine-gateway` | Prometheus 抓取状态和 Actuator 指标 | HTTP 健康接口 |
| `mine-telemetry` | Prometheus 抓取状态和 Actuator 指标 | HTTP 健康接口 |
| RabbitMQ | RabbitMQ Prometheus 插件 | RabbitMQ 管理接口 |
| 业务 PostgreSQL | PostgreSQL exporter | Actuator 数据源健康贡献者 |
| TimescaleDB | PostgreSQL exporter | 遥测只读连接健康贡献者 |
| Redis | 新增 Redis exporter | Actuator Redis 健康贡献者 |

Prometheus 的 `up` 只表示采集目标可访问，不能等同于完整业务健康。数据库需要同时参考 exporter 连接状态，RabbitMQ 需要参考节点和队列指标；前端统一展示为平台定义的 `UP`、`DEGRADED`、`DOWN` 或 `UNKNOWN`。

### 3.2 控制台指标

- 当前状态：使用最新指标并校验样本新鲜度；超过两个抓取周期没有新样本时标记为未知。
- 可用率：所选时间范围内健康样本占有效样本的比例；缺失样本不计为健康。
- 响应耗时：仅对真实存在的延迟指标计算平均值和 P95，不使用抓取耗时冒充业务耗时。
- 最近变化：从状态序列中定位最后一次状态切换时间。
- 故障区间：后端将连续异常样本合并为区间，短暂缺样与确认故障分开表达。
- 队列积压：展示 RabbitMQ 待消费消息和持续时间，不只展示瞬时峰值。
- 资源压力：展示 JVM 堆内存、数据库连接和必要的存储容量结论，不在主页面铺满所有底层指标。

阈值应由 Prometheus recording/alerting rules 或后端配置统一定义，前端不得硬编码“慢”“拥堵”等判断值。

## 4. 后端接口

### 4.1 Prometheus 查询客户端

在 `mine-server` 系统模块增加只读 Prometheus 查询端口和基础设施实现，配置至少包括：

- 是否启用；
- 内部基础地址；
- 连接和请求超时；
- 最大查询范围；
- 单次返回点数上限。

所有查询使用预定义模板、允许列表检查项和后端计算的步长。Prometheus 返回非成功状态、超时、非预期数据或部分结果时，客户端返回受控的不可用状态，不向前端泄露 PromQL、内部地址或原始响应。

### 4.2 管理端契约

建议提供以下接口：

```text
GET /api/v1/system/operations/overview?range=24h
GET /api/v1/system/operations/items/{key}/series?range=24h
GET /api/v1/system/operations
```

- `overview` 返回总体状态、指标新鲜度、各检查项摘要、可用率、耗时统计和最近变化。
- `series` 仅在用户展开某一检查项时加载状态与耗时序列，避免首屏查询所有明细。
- 现有 `operations` 保持主动即时检查语义，兼容当前页面和接口契约。

全部接口继续使用 `system:operations:view` 权限。返回值使用稳定检查项键和国际化摘要键，不返回数据库地址、账号、凭据、完整异常或内部网络拓扑。

## 5. 页面设计

### 5.1 信息层级

页面采用明亮、克制的工业控制台风格，不使用通用等高卡片网格。首屏结构如下：

1. 运行结论栏：总体状态、正常/异常/未知数量、数据更新时间、手动深度检查。
2. 时间范围与筛选：时间范围、状态、分类、服务名称搜索和耗时排序。
3. 运行状态表：异常优先，其次按分类和名称稳定排序。
4. 检查项详情：点击表格行后在页面内展开趋势区，不使用阻断式弹窗。
5. 专家入口：在具备部署条件时提供 Grafana 入口，Loki 日志仍由 Grafana 查询。

状态表包含状态、检查项、分类、可用率、平均/P95 耗时、最近变化和诊断摘要。状态、耗时和单位保持不换行；未知和未配置使用中性色，并解释原因。

详情区只为选中的检查项加载真实时间序列，展示状态区间和有业务意义的耗时曲线。没有历史数据时展示“未启用历史采集”或“所选范围没有样本”，禁止生成装饰性趋势线。

### 5.2 主题与国际化

页面优先使用 Naive UI 的 `NDataTable`、`NTag`、`NButton`、`NSelect`、`NInput`、`NAlert`、`NSkeleton` 和趋势图适配组件；布局与间距使用 UnoCSS。页面不编写 `<style>`、内联 CSS、私有样式表或硬编码颜色。

品牌橙只用于主要操作和选中状态，健康、警告和错误继续使用 Naive UI 语义色。所有状态同时使用文字、图标和颜色。全部标题、筛选项、时间范围、空状态、错误、图表说明和无障碍标签进入中英文语言包。

1366×768 下首屏应看到运行结论、筛选栏和至少五条检查项；窄屏将次要统计收进展开区，但不隐藏当前状态和异常原因。

## 6. 数据流与降级

页面首次进入时请求历史概览，不自动执行深度探测。用户点击“立即检查”后调用现有按需接口，并将结果作为单独的“即时检查”状态展示，不伪装成 Prometheus 历史样本。

降级顺序如下：

1. Prometheus 可用：展示历史概览和趋势，允许主动深度检查。
2. Prometheus 未启用或暂不可用：保留最后一次成功概览并标记陈旧，同时允许主动检查。
3. 首次加载时 Prometheus 不可用：展示即时检查模式和启用可观测 profile 的说明。
4. 单项指标缺失：只将该项标记为未知，其他检查项继续展示。
5. 主动检查失败：保留历史数据，展示带链路编号的恢复提示。

前端不得在后台高频轮询 Prometheus。刷新由页面进入、时间范围变化和用户主动操作触发；后续如需自动刷新，必须提供明确开关和刷新间隔，并在页面失焦时暂停。

## 7. 部署调整

- 增加 Redis exporter 并纳入 Prometheus 配置、Compose profile、离线镜像锁、SBOM 和冒烟测试。
- 调整 Prometheus 和数据库 exporter 的 `depends_on`，确保依赖故障时监控组件仍可启动。
- 为 `mine-server` 配置仅内部可访问的 Prometheus 地址，不新增宿主机公网暴露。
- 保持 Grafana、Prometheus 和 Loki 默认绑定 `127.0.0.1`；管理端通过后端聚合接口读取必要数据。
- 保留现有 Prometheus 告警规则，并补充 Redis 不可用、指标陈旧及必要的持续性能恶化规则。

## 8. 测试与验收

### 8.1 后端

- 单元测试固定 PromQL 模板、范围和步长计算，禁止任意查询透传。
- 使用模拟 Prometheus 响应验证正常、空序列、部分序列、超时、错误和陈旧样本。
- 控制器测试验证权限、参数上限、稳定契约和敏感信息脱敏。
- 保留现有即时探测的并发截止时间、单项失败隔离和脱敏测试。
- 集成测试验证 Redis exporter、Prometheus 抓取目标和新告警规则。

### 8.2 前端

- 验证异常优先排序、筛选、时间范围、详情按需加载和耗时不换行。
- 验证 Prometheus 不可用、指标陈旧、单项缺失、主动检查失败和空历史状态。
- 验证中英文、亮色/暗色/跟随系统、键盘操作和状态非纯颜色表达。
- 执行 `pnpm --dir mine-web check:ui-governance`、typecheck、lint、unit test 和 build。

### 8.3 部署

- 可观测性 profile 在业务服务未就绪时仍能启动 Prometheus。
- `observability-smoke.sh` 验证 Java 服务、RabbitMQ、PostgreSQL、TimescaleDB 和 Redis 目标状态。
- 离线包包含新增 exporter 镜像并通过镜像锁、许可证和 SBOM 校验。
- 断开单个依赖后，Prometheus 能持续运行并记录对应目标异常；依赖恢复后状态自动恢复。

## 9. 非目标

- 不在本期实现跨矿区集中监控和多租户指标隔离。
- 不在本期替代 Grafana 的任意 PromQL、日志检索和自定义仪表板能力。
- 不把 Prometheus 或 Loki 数据作为审计记录。
- 不新增 Alertmanager、短信、邮件或电话通知链路。
- 不承诺超过现场 Prometheus 实际保留周期的历史数据。
