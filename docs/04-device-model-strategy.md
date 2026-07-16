# 04 物模型、字段映射与策略中心

## 1. 为什么需要统一模型

不同厂商可能把同一个含义写成 rpm、engineSpeed、n1 或某个二进制偏移。平台不能让业务页面、告警和统计直接依赖这些厂商字段。

数据经过三层：

~~~text
厂商原始字段
→ 协议解析字段
→ 物模型属性
→ 矿山业务指标
~~~

示例：

~~~text
bytes[12..13] = 0x0A8C
→ vendor_engine_speed = 2700
→ engine.rpm = 1350 rpm
→ vehicle.engineRunning = true
~~~

协议负责“从字节中取出什么”，字段映射负责“转换成哪个标准属性”，物模型负责“该属性是什么类型和语义”，业务规则负责“它对矿山作业意味着什么”。

## 2. 核心概念

| 概念 | 说明 |
|---|---|
| Product | 一类设备或终端，关联默认模型、协议和策略 |
| Device | 具体实例，具有身份、凭据和当前配置包 |
| ThingModel | 产品能力定义，包含属性、事件和命令 |
| Protocol | 原始报文的帧和字段规则 |
| Mapping | 协议字段到物模型属性的映射 |
| Strategy | 告警、保留、质量、弱网等运行策略 |
| ConfigPackage | 一组不可变版本引用，保证原子发布 |
| ConfigRelease | 某配置包对某作用范围的发布事实 |

## 3. 物模型

### 3.1 属性

每个属性至少包含：

- identifier：稳定标识，例如 engine.rpm。
- name：中文显示名称。
- dataType：int、long、decimal、boolean、string、enum、dateTime、geoPoint、vector3。
- unit、precision、min、max、step。
- access：readOnly、readWrite。
- required、nullable、defaultValue。
- latestEnabled：是否保存当前值。
- historyEnabled：是否保存历史。
- realtimeEnabled：是否进入 WebSocket。
- alarmEnabled：是否可用于告警。
- retentionClass：保留等级，而不是任意设备天数。
- qualityPolicy：非法值如何标记或丢弃。

identifier 发布后不可改变语义。需要更名时增加新属性并建立迁移映射。

### 3.2 事件

事件表示已经发生的事实，例如：

- engine.overheat
- device.offline
- uwb.enterZone
- vehicle.arriveWaypoint
- operation.completed

事件定义包含 payload schema、严重级别、是否需要确认和历史保留等级。

### 3.3 命令

命令定义包含：

- identifier、名称、用途和危险等级。
- 输入参数 schema。
- 输出/回复 schema。
- 设备在线要求、TTL、超时和重试策略。
- 操作权限码。
- 对应协议编码器标识。

## 4. 显示元数据

物模型与页面展示解耦。显示 schema 描述：

- 分组、顺序、单位、精度和图标。
- widget：数字、仪表盘、进度条、开关、枚举、趋势图。
- 列表是否显示、详情是否显示、移动端是否隐藏。
- 正常/警告/危险阈值的颜色语义。
- 历史曲线默认时间窗和聚合粒度。

显示元数据不能包含可执行脚本。前端只从白名单组件注册表选择 widget。

## 5. 字段映射

映射能力包括：

- rename：厂商字段映射到标准 identifier。
- scale/offset：数值换算。
- enum：厂商码表转标准枚举。
- bit extraction：从整数提取状态位。
- conditional：受控条件分支。
- default/null：缺失和无效值处理。
- unit conversion：例如 kPa 到 MPa。
- quality：越界、无效码、陈旧值的质量标记。

一期不提供任意表达式语言。条件和函数来自后端注册白名单，例如 multiply、divide、clamp、enum、coalesce、bit、dateParse。每个函数有确定输入输出和测试用例。

## 6. 策略类型

| 策略 | 主要配置 | 运行消费者 |
|---|---|---|
| 物模型 | 属性、事件、命令、显示 schema | gateway、telemetry、server、web |
| 协议解析 | 帧、消息、字段、CRC、编码 | gateway |
| 字段映射 | rename、换算、枚举、质量 | telemetry |
| 数据质量 | 范围、跳变、必填、陈旧 | telemetry |
| 在线离线 | 心跳、超时、迟到容忍 | gateway/telemetry |
| 告警 | 条件、持续时间、恢复、抑制 | telemetry |
| 保留 | 原始、历史、事件、审计期限 | telemetry/server |
| 聚合/派生 | 分钟/小时统计、派生指标 | telemetry |
| 转发 | 目标、过滤、脱敏、重试 | telemetry |
| 命令模板 | 参数、权限、TTL、重试 | server/gateway |
| 弱网 | 去重、乱序、补传、缓存等级 | gateway/telemetry/edge |
| 围栏 | 区域、进入/离开、停留 | telemetry |

## 7. 作用范围和继承

优先级从低到高：

~~~text
产品默认
→ 设备组覆盖
→ 单设备覆盖
~~~

规则：

- 未配置的字段继承下一级来源。
- 单设备值覆盖所有组和产品值。
- 同一设备属于多个组且同一字段在相同优先级给出不同值时，禁止发布。
- 前端“有效配置”面板显示每个值、来源范围和版本。
- 设备组成员变化会重新计算影响设备，但不修改已有发布历史。
- 保留策略优先按数据等级、产品或组配置，避免每设备碎片化删除。

Phase 2 当前已落地确定性的三级有效值引擎。设备组优先级数值越大，覆盖优先级越高；同一优先级的多个组为同一字段提供相同值时可安全合并，提供不同值时返回稳定的字段、优先级、设备和设备组冲突证据，整笔发布、成员调整或优先级调整均不提交。产品和单设备在同一策略类型下各只允许一个当前发布片段，单设备始终拥有最终覆盖权。

首个进入生产白名单和运行消费链路的策略类型是 `ONLINE_STATUS`，当前只开放 `offlineThresholdSeconds`，范围为 1～604800 秒。控制面支持产品、设备组和单设备草稿、幂等发布、不可变历史、回滚和逐字段来源查询；未配置有效策略的设备继续使用设备接入配置中的离线阈值，禁止把尚无运行消费者的字段伪装成已生效能力。

设备组编码在产品内永久唯一。成员关系使用 `[effective_from, effective_to)` 历史区间保存，成员替换只关闭差异关系；设备或设备组归档会结束当前关系。服务层和 PostgreSQL 触发器共同阻止跨产品、已归档设备和无效组关系进入有效配置计算。

## 8. 配置包

设备运行时不能分别加载“新协议 + 旧物模型 + 新映射”。因此发布单位是 ConfigPackage：

~~~json
{
  "packageVersion": 18,
  "thingModelVersion": 7,
  "protocolVersion": 3,
  "mappingVersion": 5,
  "qualityVersion": 2,
  "alarmVersion": 9,
  "retentionVersion": 4,
  "weakNetworkVersion": 2,
  "contentHash": "sha256:..."
}
~~~

包只引用已经发布或锁定的不可变版本。发布前检查跨资源兼容性，例如 mapping 的目标属性必须存在于 thingModelVersion 7。

Phase 2 当前配置包由已发布物模型版本、已发布 MQTT JSON 协议映射版本以及可直接编译的完整非敏感快照组成。`contentHash` 是规范化快照的 SHA-256，不受 JSON 对象字段顺序影响；密钥、设备密码和 TLS 私钥不得进入快照。

`ConfigPackage` 表达“内容是什么”，`ConfigRelease` 表达“何时、由谁、因为什么把这个包应用到产品”。二者均为只增不改：数据库触发器禁止更新或删除，回滚必须复用历史包创建更高 `releaseSequence` 的新发布事实。

## 9. 生命周期

~~~mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> TESTING
    TESTING --> DRAFT: 测试失败
    TESTING --> READY: 全部通过
    READY --> PUBLISHED: 管理员发布
    PUBLISHED --> SUPERSEDED: 新版本生效
    PUBLISHED --> ROLLBACK_RELEASED: 回滚为新发布
~~~

发布流程：

1. 保存草稿并进行乐观锁校验。
2. 结构和语义校验。
3. 协议黄金样本回归。
4. 计算影响产品、组、设备和在线连接数。
5. 显示差异、风险、断连影响和操作原因。
6. 管理员二次确认后直接发布。
7. 记录发布人、时间、原因、IP、版本和结果。

不设审批流，但发布不是无校验的保存按钮。

Phase 2 的服务端发布门禁不会信任浏览器中已经显示的测试结果。每次发布都会在事务外准备阶段重新读取当前样本和版本，使用 Gateway 同源引擎编译并全量回放；任一样本失败、引用版本未发布、存在 `EXPRESSION` 转换或快照无法规范化时均拒绝创建配置包和发布事实。发布与回滚都要求原因和 `Idempotency-Key`，相同管理员重复提交不会产生两个发布。

## 10. 热、温、冷配置

### 10.1 热更新

- 映射、物模型显示元数据。
- 告警、质量、在线离线、保留和聚合。
- 命令模板和弱网参数。

消费者编译新快照并原子替换，不重启进程。

### 10.2 温更新

- TCP 监听端口、TLS 证书、设备协议切换。
- 可能需要重新绑定监听器或断开受影响设备连接。
- 发布预览必须明确列出影响连接。

### 10.3 冷更新

- JVM 参数、数据库 schema、RabbitMQ 集群配置。
- 新 Java 协议插件 JAR。
- 基础镜像和操作系统参数。

冷更新通过维护窗口、滚动升级或停机升级处理，不能伪装成热加载。

## 11. 运行节点加载

1. ConfigReleaseEvent 只携带 releaseId、packageId、hash 和目标。
2. 节点通过受权 API 拉取完整快照。
3. 校验 hash、schema、依赖版本和本地编译结果。
4. 成功后原子替换 active snapshot。
5. 上报 LOADED 或 FAILED 及错误。
6. 超时/离线节点显示 PENDING。
7. 节点重启时先加载本地最后成功快照，再与 server 对账。

控制面不可用时，数据面继续使用 last-known-good。

Gateway 的落地顺序是“拉取 → 定位信息比对 → 哈希校验 → 本地编译 → CAS 激活 → 原子持久化 → 回报 LOADED → RabbitMQ ACK”。本地缓存使用有界 JSON、同目录临时文件和文件替换，POSIX 文件系统上权限收紧为仅运行用户读写；单机 Compose 使用独立持久卷，并先由一次性初始化容器把目录授权给 UID/GID 10001。缓存损坏会让节点启动失败并暴露运维问题，不能静默退回未知配置。

同序号同发布同哈希属于幂等重投；低序号、同序号异发布/异哈希、产品编码冲突或编译失败均拒绝。回滚由于拥有更高发布序号，可以像普通发布一样无重启原子切换。

在线状态有效策略在 `mine-server` 内按设备批量解析。默认本机缓存上限为 10000 台设备、TTL 为 30 秒，发布、回滚、成员、优先级和归档事务仅在提交成功后失效受影响设备；刷新失败时，已有有效值继续使用本机 last-known-good 并以 5 秒退避重试，从未成功解析的无效策略则暴露稳定错误。缓存命中/未命中、解析结果、last-known-good 回退、失效原因和解析耗时使用固定枚举标签记录，不携带设备或发布标识。

Gateway 配置分发会记录 `loaded`、`permanent_failure`、`transient_failure` 和 `invalid_message` 四类低基数结果，以及解码、拉取、校验、激活、持久化、状态回报六个阶段。永久快照错误必须先向控制面写入脱敏、最多 500 字符的 `FAILED` 证据再拒绝消息；证据回报失败仍 NACK 重投。Prometheus 对持续 1 分钟的永久失败产生严重告警，并对 10 分钟内持续瞬时重试产生警告。

## 12. 保留和清理

建议数据等级：

| 等级 | 示例 | 默认思路 |
|---|---|---|
| HOT | 当前值、近期位置和告警 | 快速查询，短期细粒度 |
| WARM | 常规历史和运营明细 | 中期原粒度或小时聚合 |
| COLD | 审计、关键事件和归档文件 | 长期保存，低频访问 |
| EPHEMERAL | 调试原始报文 | 短期自动删除 |

TimescaleDB Apache 2 基线不依赖受限许可证功能。保留和聚合任务由平台自己的受控任务执行，并以分区/chunk 时间范围批量处理，禁止逐行删除海量遥测。

## 13. 安全约束

- 策略 JSON 必须有 schema 和大小限制。
- 正则表达式使用安全实现或限制复杂度，防止 ReDoS。
- 发布 API 需要具体权限码，不用笼统 admin。
- 密钥、设备密码和 TLS 私钥不进入配置包明文。
- 导出包默认脱敏；包含 secret 的迁移包单独加密和审计。
