# 设备接入状态与手动检查设计

## 目标

在现有端点配置与 DNS/TCP/TLS 诊断之上，把设备接入拆成端点可达、传输会话、有效首帧、数据落库四层，并允许接入工程师手动执行一次完整检查。设备台账持续展示可解释的接入状态，而不是单一且容易误导的在线布尔值。

## 状态模型

| 层级 | 证据 | 成功口径 |
|---|---|---|
| ENDPOINT | mine-server 主动探测 | 配置、DNS、TCP 与可选 TLS 通过 |
| TRANSPORT | RabbitMQ 管理事件/连接 | MQTT 凭据存在活动连接；无会话来源的 TCP 模式返回未知 |
| ACTIVITY | telemetry 持久读模型 | 至少一条身份、协议和数据质量校验后的有效首帧已提交 |
| DATA | TimescaleDB 最新状态 | 最近一次有效报文已经提交，且接收时间未超过设备离线阈值 |

总体状态使用 `NOT_CONFIGURED`、`WAITING_FOR_DEVICE`、`TRANSPORT_ONLY`、`ONLINE`、`STALE`、`FAILED`、`UNKNOWN`。任何依赖不可用都显示未知，不得伪装成设备离线。

## 数据流

Telemetry 在现有历史事务中维护 `telemetry_latest_state.first_seen_at` 和 `last_received_at`。Server 使用只读遥测连接批量读取当前页设备的首帧与最后接收时间，并结合接入配置的离线阈值计算状态。MQTT 传输会话只在用户点击手动检查时通过 RabbitMQ 管理接口按设备活动凭据查询，避免设备列表轮询管理面。

手动检查接口独立执行四层检查：端点层复用安全探针；传输层查询 Broker；活动和数据层查询持久遥测状态。各层独立返回 `PASSED`、`FAILED`、`WAITING`、`UNKNOWN`、`SKIPPED` 及稳定原因码，前端负责中英文展示。

## 页面交互

设备台账新增“接入状态”和“最后上报”列。接入配置抽屉保留“测试端点”，并新增主要操作“检查接入状态”；结果以四层卡片展示证据时间、耗时和建议。配置存在未保存修改时，两种测试都不可执行。

## 安全与失败处理

手动检查复用 `device:connection:test` 权限、CIDR 白名单、全局超时和并发上限。RabbitMQ 管理请求只返回连接是否存在和脱敏摘要，不返回凭据。Telemetry/RabbitMQ 不可用时单层返回未知，接口仍返回完整结果并写操作审计。
