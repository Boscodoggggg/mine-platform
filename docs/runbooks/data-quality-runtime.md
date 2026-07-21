# 数据质量策略运行手册

## 1. 适用范围

本手册用于 `DATA_QUALITY` 策略的发布观察、质量异常排查和安全回退，适用于管理服务、Telemetry、RabbitMQ、PostgreSQL/TimescaleDB 和 Prometheus 已正常部署的环境。

当前规则只覆盖整帧时间、属性必填和数值上下限。跳变率、字符串/枚举规则、跨属性表达式、告警联动和现场阈值自动调优不在本版本范围内。任何数据库查询都应使用只读运维账号；禁止通过手工修改策略镜像表“修复”运行状态。

## 2. 发布前检查

1. 确认属性标识与已发布物模型一致，并记录变更工单、责任人和预期影响设备范围。
2. 优先在产品默认配置通用时间窗口，用设备组或单设备覆盖例外；同优先级设备组不得为同一字段配置不同值。
3. 时间规则至少结合设备时钟同步能力和矿区链路时延设定，数值阈值必须有厂商量程或业务基线，不得凭页面示例直接用于生产。
4. 在策略工作台选择“数据质量策略”，检查最终有效值、来源层级、发布记录和内容哈希，再填写可审计的发布原因。
5. 发布或回滚必须通过受权 API/工作台并携带稳定 `Idempotency-Key`；不得直接修改 `dev_strategy_*` 或 `telemetry_*` 表。

## 3. 发布后确认

先观察策略事件是否完成原子激活：

~~~promql
increase(mine_telemetry_strategy_distribution_total{outcome="loaded"}[10m])
~~~

确认没有新增永久失败或持续重试：

~~~promql
increase(mine_telemetry_strategy_distribution_total{outcome=~"permanent_failure|invalid_message"}[10m])
increase(mine_telemetry_strategy_distribution_total{outcome="transient_failure"}[10m])
~~~

使用 Telemetry 数据库只读账号核对当前运行水位和设备镜像：

~~~sql
SELECT product_id, runtime_revision, snapshot_id, item_count, content_hash, applied_at
FROM telemetry_strategy_runtime_revision
WHERE strategy_type = 'DATA_QUALITY'
ORDER BY applied_at DESC;

SELECT product_id, runtime_revision, snapshot_id, count(*) AS device_count
FROM telemetry_data_quality_policy
GROUP BY product_id, runtime_revision, snapshot_id
ORDER BY runtime_revision DESC;
~~~

运行修订必须单调增加，快照条目数应与本次影响范围一致。策略未配置到某台设备时，本地没有对应镜像是合法状态，Telemetry 会保持原有质量语义。

## 4. 质量异常排查

先按稳定规则编码聚合，不要把设备标识加入 Prometheus 标签：

~~~promql
sum by (rule, result) (increase(mine_telemetry_quality_rule_hits_total[10m]))
~~~

再用 TimescaleDB 证据表定位有限样本：

~~~sql
SELECT created_at, device_id, property_id, rule_code, result_quality,
       observed_value, threshold_value, policy_runtime_revision,
       policy_snapshot_id, policy_content_hash
FROM telemetry_quality_evidence
WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '30 minutes'
ORDER BY created_at DESC
LIMIT 200;
~~~

按规则处理：

- `FUTURE_TIMESTAMP`：检查设备、Gateway、Telemetry 和数据库主机的时间源与最大时钟偏差。
- `STALE_TIMESTAMP`：区分设备缓存补传、RabbitMQ 积压、链路时延和阈值过紧。
- `REQUIRED_MISSING`：核对物模型、协议映射、设备固件版本和报文类型，不得伪造缺失值。
- `BELOW_MINIMUM` / `ABOVE_MAXIMUM`：核对单位换算、量程、传感器故障、映射版本和策略阈值。

质量降级不会静默删除原始数值。排查时应同时核对遥测历史的属性质量、事件标识和证据中的策略修订，避免把旧策略产生的证据归因到当前版本。

## 5. 告警处置

### `MineTelemetryStrategyLoadFailed`

1. 保留死信消息和关联日志，不要清空队列后再调查。
2. 检查快照完整性、控制面内部快照 API、Telemetry 当前运行修订和最后正确镜像。
3. 确认损坏快照没有覆盖当前镜像；修复配置后创建更高修订的新发布或回滚发布。

### `MineTelemetryStrategyRetrying`

1. 检查管理服务可用性、服务间 DNS/网络、Telemetry 数据库连接和策略队列积压。
2. 瞬时失败应保持消息重投，不能通过提前 ACK 消除告警。
3. 恢复后确认 `loaded` 增长、队列最老消息年龄下降且运行修订完成追赶。

### `MineTelemetryDataQualitySpike`

1. 按 `rule/result` 确认增长类型，再抽取质量证据，禁止只看总数。
2. 判断是设备群故障、时钟漂移、消息积压、协议映射错误还是阈值不合理。
3. 若新策略造成大面积误判，按第 6 节回滚；若是设备或链路故障，保留策略和证据并进入设备运维流程。

当前质量突增阈值是仓库默认基线，正式矿区必须结合设备规模和上报频率调优，并验证告警路由和值班响应；未完成调优不能把“没有告警”解释为数据正常。

## 6. 安全回滚

1. 在策略工作台选择目标产品和“数据质量策略”，找到已知正确的历史发布。
2. 检查该发布的作用范围、内容哈希和逐字段值，填写不少于 4 个字符的回滚原因并二次确认。
3. 系统会复用历史版本创建更高发布序号、运行修订和新快照；历史记录不会被修改。
4. 重复提交时复用同一个 `Idempotency-Key`，直到得到确定结果；超时后不得换键盲目重试。
5. 按第 3 节确认新修订 `loaded`，并观察质量命中是否回归预期。

禁止降低数据库修订号、覆盖本地镜像、删除 Inbox 或直接更新策略表。上述操作会破坏至少一次投递、不可变历史和重启恢复语义。

## 7. 升级与恢复不变量

- 控制面快照、Telemetry 运行水位和单设备策略哈希必须能够关联。
- 数据库提交失败时不得更新 Redis 或发布实时事件。
- RabbitMQ 重投不得重复生成质量证据，也不得因期间策略变化改写已提交事件。
- 暂时加载失败时继续使用最后正确镜像；永久错误必须可从死信和指标定位。
- 备份恢复、双节点追赶、断电、容量和 24/72 小时长稳属于独立现场门禁，本手册中的只读检查不能替代这些验收。
