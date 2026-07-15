# MQTT 真实设备现场接入与 24 小时验收手册

> 适用范围：Phase 1、单矿区私有化部署、RabbitMQ MQTT 插件接入的真实 MQTT 设备
>
> 不适用范围：厂商私有 TCP、未经评审的控制命令、以模拟器代替生产放行
>
> 关联契约：`docs/contracts/message-envelope-v1.json`、`docs/contracts/phase-1-device-acceptance-v1.schema.json`

## 1. 目标与放行边界

本手册把首台真实设备从资料收集、建档、凭据签发、MQTT 接入、遥测核验、弱网恢复、低风险命令到 24 小时观察串成一个可审计流程。执行结束后必须形成一份符合验收记录契约的 JSON 证据和一份现场结论。

以下条件全部满足，首台设备闭环才可判定为 `PASS`：

- 使用矿区正式 MQTTS 地址、正式 CA 和平台签发的独立设备凭据。
- 上行数据能够经过 RabbitMQ、`mine-gateway`、`mine-telemetry` 写入 TimescaleDB 和 Redis，并在管理端实时展示。
- 设备重复报文不产生重复历史，乱序补传不覆盖新启动周期的最新值。
- 至少完成一次真实链路中断、恢复和补传，且消息积压最终归零。
- `QUERY_STATUS` 命令获得设备真实终态，不能只证明平台已发送。
- 连续观察不少于 24 小时，并记录消息率、失败率、延迟、资源、断线、补传和未知报文。
- 没有未处理的高风险缺陷；任何降级放行都要由矿区负责人和平台负责人共同签字。

模拟器、单元测试和 Testcontainers 只能作为接入前基线，不能替代真实设备和真实矿区网络。

## 2. 角色和安全职责

| 角色 | 主要职责 |
|---|---|
| 矿区生产负责人 | 确认测试窗口、设备可操作范围、紧急停止和放行结论 |
| 设备厂商工程师 | 提供协议、固件行为、时钟、缓存、重连和命令语义 |
| 平台接入工程师 | 建档、身份映射、报文核验、日志和指标证据 |
| 平台运维工程师 | TLS、主机、Compose、备份、监控、资源和回退 |
| 安全审计人员 | 凭据交接、最小权限、命令审计和证据留存 |

生产设备命令必须先由生产负责人批准。Phase 1 只允许低风险只读模板 `QUERY_STATUS`；不得借验收窗口试发启动、停车、转向、制动或参数修改命令。

## 3. 接入前资料清单

开始前为每个设备建立工单，至少收集：

- 矿区、区域、车辆或设备资产编码、设备名称、厂商、型号、固件版本。
- 平台 `productKey` 和矿区唯一 `deviceCode`；编码发布后不原地修改。
- MQTT 版本、KeepAlive、QoS、持久会话、重连退避、最大在途数和离线缓存容量。
- 设备是否支持 TLS 1.2+、SNI、私有 CA、证书链更新和系统时间同步。
- 完整上行主题、下行主题、至少 20 份正常样本及异常、空值、极值、重启、补传样本。
- 报文字段的类型、单位、精度、量程、枚举、无效值、采样周期和上报周期。
- `bootId` 生成和持久化规则、`sequence` 起点/上限/回绕规则、`occurredAt` 时区和精度。
- 掉电时缓存是否丢失、缓存写满策略、恢复后的补传顺序与限速。
- `QUERY_STATUS` 请求、设备已接收 ACK、成功/拒绝/失败回复的真实样本。
- 联系人、现场窗口、可接受中断、回退动作和禁止操作清单。

任一关键字段不清楚时，验收状态写 `BLOCKED`，不能靠猜测协议进入生产。

## 4. 平台和网络前置检查

在平台主机的仓库根目录执行：

~~~bash
bash deploy/scripts/preflight.sh
bash deploy/tests/standalone-smoke.sh
docker compose --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml ps
~~~

预期所有核心服务为 `healthy`，HTTPS、MQTTS、PostgreSQL、TimescaleDB、RabbitMQ、Redis 均通过。现场还必须检查：

- 设备网只能访问平台 MQTTS 端口，数据库、Redis、AMQP 管理端口不对设备网开放。
- 设备 DNS 能解析 Broker 名称，证书 SAN 与名称一致，证书链和有效期正确。
- 平台、RabbitMQ、设备时钟有可信时间源；记录验收开始时的最大时钟偏差。
- PostgreSQL、TimescaleDB 已完成可恢复备份，磁盘剩余空间和 inode 满足观察窗口。
- RabbitMQ、Redis、数据库和三个 Java 服务的监控 target 为 `up`。
- 日志、截图和验收 JSON 中不记录明文密码、私钥或完整令牌。

## 5. 产品、设备和凭据

产品和设备台账优先在管理端“设备管理”完成；当前前端尚未提供凭据操作页面，凭据签发、轮换和吊销必须由有权限的管理员调用 API：

1. 创建或选择产品，确认 `productKey`、设备类型和状态 `ACTIVE`。
2. 创建设备，填写矿区唯一 `deviceCode`，状态设为 `ENABLED`。
3. 使用 `device:credential:create` 权限为设备签发 MQTT 凭据。
4. 立即把一次性 `secret` 交付到现场密码管理工具，不复制到工单、聊天、YAML 或 Git。
5. 记录返回的内部设备 UUID、凭据 UUID、`username/clientId`、主题根和版本，但证据中不得保存 `secret`。

凭据 API 与权限为：

- `POST /api/v1/devices/{deviceId}/credentials`：`device:credential:create`
- `POST /api/v1/device-credentials/{credentialId}/rotate`：`device:credential:rotate`，请求体为 `{"version": 当前版本}`
- `DELETE /api/v1/device-credentials/{credentialId}?version={version}`：`device:credential:revoke`

调用 API 时应先登录并使用登录响应返回的 `tokenName` 作为请求头名称；不要在脚本里假定固定令牌头。可在受控终端中把令牌放入环境变量，将一次性响应留在内存并直接写入权限为 `0600` 的仓库外密码文件：

~~~bash
umask 077
MINE_AUTH_HEADER_FILE="$(mktemp)"
trap 'rm -f "${MINE_AUTH_HEADER_FILE}"' EXIT
printf '%s: %s\n' "${MINE_TOKEN_NAME}" "${MINE_TOKEN_VALUE}" > "${MINE_AUTH_HEADER_FILE}"
unset MINE_TOKEN_VALUE
credential_response="$(curl --fail --silent --show-error \
  -X POST --header "@${MINE_AUTH_HEADER_FILE}" \
  "https://<平台域名>/api/v1/devices/${DEVICE_ID}/credentials")"
printf '%s' "${credential_response}" | jq -r '.secret' > /受控路径/device-secret
printf '%s' "${credential_response}" | jq 'del(.secret)'
unset credential_response
~~~

控制台输出只能显示删除 `secret` 后的响应；密码文件要立即导入现场密码管理工具并安全删除临时副本。设备凭据一设备一账号，不允许多台设备共用。

当前轮换是有中断轮换：调用 rotate API 会立即替换 RabbitMQ 密码并断开该用户的现有连接，新 `secret` 只能在调用成功后取得。必须先批准维护窗口，再调用轮换 API，把新密钥安全配置到设备并重连验证；旧密钥不能继续使用。新密钥配置失败时只能再次轮换生成另一把新密钥，或者吊销凭据并停止接入，不能尝试回退旧密钥。

## 6. Gateway 身份映射

Phase 1 使用 `deploy/config/gateway/device-identities.json` 作为运行时身份映射兜底。把签发结果加入 `devices`，示例：

~~~json
{
  "version": 2,
  "devices": [
    {
      "productKey": "haul-truck",
      "clientId": "device-平台签发标识",
      "deviceId": "019f63e2-071f-706a-bd12-4dad7150e75d",
      "deviceCode": "truck-001",
      "tenantScope": "mine-site-001",
      "protocolVersion": "mqtt-json-v1"
    }
  ]
}
~~~

要求：

- `version` 每次发布递增，JSON 修改必须经过评审并保留发布记录。gateway 会拒绝低版本以及同版本异 SHA-256；同版本同 SHA-256 只作为幂等重复读取。
- `clientId` 必须等于平台签发值；不要把业务 `deviceCode` 当作 MQTT Client ID。
- `deviceId` 必须是平台内部 UUID，`productKey` 与 MQTT 主题一致。
- 当前 Compose 挂载整个 `deploy/config/gateway/` 目录，发布前用 `jq empty` 校验候选 JSON，保存 SHA-256 和上一版副本，再把候选文件原子重命名为 `device-identities.json`，避免 loader 读到半文件。由旧版本升级且容器仍是“单文件 bind mount”时，必须先重建 gateway 容器一次，确认目录挂载已生效后再使用原子发布。
- 文件写入后等待一个轮询周期，不重启服务。先在 gateway 容器内查询内部管理端口，确认 `deviceIdentitySnapshot.version`、`sha256` 和 `deviceCount` 与发布记录一致，再用新 `clientId` 做 MQTTS 连接和单帧上报，并在 gateway、历史和最新值三处验证后，才能认定快照生效。查询示例：

~~~bash
docker compose --env-file deploy/env/standalone.env \
  -f deploy/compose/compose-standalone.yml exec -T mine-gateway bash -ec '
    exec 3<>/dev/tcp/127.0.0.1/18081
    printf "GET /actuator/info HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3
    cat <&3
  ' | tr -d '\r' | sed -n '/^{/,$p' | jq '.deviceIdentitySnapshot'
~~~

- 如果功能探针失败，使用“上一版 devices 内容 + 更高 version”重新发布并调查，不允许直接把旧低版本文件覆盖回去，也不允许带着未确认配置继续验收。

后续前台动态配置发布上线后，应由发布事件维护同一份运行时映射；本文件不再作为人工主入口，但仍可保留为灾备快照。

## 7. 设备连接参数

生产接入必须使用：

- Broker：`ssl://<矿区正式域名>:8883`
- MQTT 用户名：平台签发的 `username`
- Client ID：平台签发的 `clientId`
- 密码：一次性 `secret`
- 上行：`mine/v1/{productKey}/{clientId}/up/telemetry`
- 下行命令：精确订阅 `${downTopic}/command`；如设备实现需要通配，可订阅 `${downTopic}/#`。响应中的 `downTopic` 只是以 `/down` 结尾的前缀，不是完整命令主题。
- QoS：按照设备协议评审值，弱网设备不得用“发完即丢”的业务假设
- 会话：按持久会话设计，明确 Session Expiry/Clean Session 行为

禁止关闭服务端证书校验，禁止把 MQTTS 临时改成公网明文 MQTT。首次连接后同时检查 RabbitMQ 连接、设备用户名、vhost、订阅和权限，确认该设备不能发布或订阅其他设备主题。

## 8. 首批遥测核验

让设备先发送小批量数据，逐层检查：

1. RabbitMQ 设备连接存在，上行消息进入平台链路，没有持续 `unacked`。
2. gateway 日志中身份、主题、协议版本正确，无未知设备和解析失败。
3. telemetry 日志无持续重投；TimescaleDB 有设备历史点。
4. Redis 最新值的 `bootId`、`sequence`、值和时间与设备原始报文一致。
5. 管理端实时卡片刷新，单位、精度、枚举、空值和更新时间正确。
6. 使用 `device:telemetry:view` 权限查询最新值和历史，确认两者表达同一设备事实。

两个查询接口都有必填参数。令牌头沿用登录响应的动态 `tokenName`，示例：

~~~bash
curl --fail --silent --show-error --get \
  --header "@${MINE_AUTH_HEADER_FILE}" \
  --data-urlencode 'propertyIds=engine.rpm' \
  --data-urlencode 'propertyIds=vehicle.speed' \
  "https://<平台域名>/api/v1/devices/${DEVICE_ID}/latest"

curl --fail --silent --show-error --get \
  --header "@${MINE_AUTH_HEADER_FILE}" \
  --data-urlencode 'propertyId=engine.rpm' \
  --data-urlencode 'from=2026-07-15T00:00:00Z' \
  --data-urlencode 'to=2026-07-16T00:00:00Z' \
  --data-urlencode 'aggregation=RAW' \
  --data-urlencode 'page=1' \
  --data-urlencode 'size=100' \
  "https://<平台域名>/api/v1/devices/${DEVICE_ID}/telemetry"
~~~

`from` 包含、`to` 不包含；后续分页必须原样带回首页响应的 `watermark`，避免观察期间新增数据导致翻页漂移。

至少抽查 20 帧并保存脱敏原始报文摘要。逐字段核对：原始字段、统一属性标识、类型、单位换算、缩放、质量码、设备发生时间、平台接收时间、`bootId`、`sequence` 和 `replayed`。未知字段不能静默丢弃，应记录数量、样本哈希和处置结论。

## 9. 幂等、乱序、重启和真实弱网

按生产负责人批准的窗口执行：

1. 记录断网前最后一个 `bootId/sequence`、RabbitMQ 队列和最新值。
2. 通过现场可控手段中断真实链路；优先断设备网络，不用优雅 MQTT `DISCONNECT` 冒充骤断。
3. 保持中断到能覆盖设备实际缓存行为，记录离线发现时间和 KeepAlive 超时。
4. 恢复网络，记录首帧时间、重连次数、积压峰值、补传速率和清空耗时。
5. 核对历史没有遗漏或重复；重复事件按同一 `eventId` 幂等。
6. 核对乱序旧帧进入历史但不回退最新值。
7. 安排一次设备重启，确认新 `bootId` 可从设备定义的序号起点开始。
8. 重启后如补传旧启动周期，确认旧周期不能覆盖新周期最新值。

若设备没有 `bootId`、序号或可靠发生时间，必须先完成协议适配设计。不能仅用平台接收时间推断设备数据顺序后直接放行。

## 10. 低风险命令闭环

在管理端命令中心选择目标设备和 `QUERY_STATUS` 下发。当前 UI/API 尚无业务原因字段，因此生产负责人批准的工单号只能记录在仓库外验收证据中，不能宣称已写入平台命令审计；这是生产放行前需补齐的安全能力。API 等价请求使用唯一 `Idempotency-Key`：

~~~json
{
  "deviceId": "019f63e2-071f-706a-bd12-4dad7150e75d",
  "templateKey": "QUERY_STATUS",
  "parameters": {},
  "ttlSeconds": 300,
  "timeoutSeconds": 30
}
~~~

调用者需要 `command:instance:create` 权限，完整 API 示例为：

~~~bash
IDEMPOTENCY_KEY="device-acceptance-$(date -u +%Y%m%dT%H%M%SZ)"
jq -n --arg deviceId "${DEVICE_ID}" '{
  deviceId: $deviceId,
  templateKey: "QUERY_STATUS",
  parameters: {},
  ttlSeconds: 300,
  timeoutSeconds: 30
}' | curl --fail --silent --show-error \
  -X POST \
  --header "@${MINE_AUTH_HEADER_FILE}" \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  --data-binary @- \
  'https://<平台域名>/api/v1/commands'
~~~

通过条件必须看到完整时间线 `CREATED -> QUEUED -> SENT -> DEVICE_RECEIVED -> SUCCEEDED`，并核对真实设备回复。`REFUSED` 或 `FAILED` 可以证明设备给出了拒绝/失败终态，但本次命令验收应判失败；`TIMEOUT` 是平台生成的超时终态，不能证明设备回复。只到 `SENT` 也不算闭环。重复提交相同幂等键不得产生第二个命令实例。核对操作人、设备、模板、参数摘要、时间和回复可审计，并把外部批准工单号与命令 ID 一起写入验收证据。

## 11. 24 小时观察

观察窗口不得短于 24 小时，建议覆盖一个完整生产班次切换和至少一个网络波动时段。开始和结束分别保存 Compose 状态、队列、磁盘和 `docker stats --no-stream` 快照。

每 5 分钟或使用 Prometheus 自动采样以下指标：

| 分类 | 必记指标 |
|---|---|
| 流量 | 设备消息总数、平均/峰值消息率、字节率、属性点率 |
| 可靠性 | 解析失败、鉴权失败、重复、缺口、NACK、死信、未知报文 |
| 延迟 | 设备发生到平台接收、接收到持久化、持久化到前端，记录 p50/p95/p99/max |
| 弱网 | 断线次数、最长离线、重连次数、积压峰值、补传清空时间 |
| RabbitMQ | ready、unacked、consumer、连接、磁盘/内存水位 |
| 数据层 | PostgreSQL/TimescaleDB 连接和磁盘增长、Redis 内存和拒绝写入 |
| 应用 | gateway/telemetry/server CPU、内存、重启、GC、错误率 |
| 业务 | 最新值停滞、历史缺口、命令成功/拒绝/超时和响应耗时 |

失败率分母和分子必须写清楚，例如“解析失败帧数/收到的设备帧数”，不能只填百分比。没有流量时延迟和成功率应写 `null` 并解释，不能填 0 冒充正常。

验收原始记录按 `docs/contracts/phase-1-device-acceptance-v1.schema.json` 保存。建议目录为仓库外受控位置：

~~~text
acceptance/<矿区>/<设备编码>/<开始时间>/
├── acceptance.json
├── environment.txt
├── compose-ps-start.txt
├── compose-ps-end.txt
├── resource-start.txt
├── resource-end.txt
├── rabbit-queues.csv
├── metrics-export/
├── sanitized-message-samples/
└── incident-notes/
~~~

生产日志和现场数据可能包含敏感信息，未经脱敏不得提交开源仓库。

## 12. 判定、回退与离场

出现以下任一情况立即停止验收并回退：

- 错误设备收到命令、越权主题访问或凭据泄漏。
- 数据映射错误可能影响生产判断，或者旧数据覆盖新数据。
- RabbitMQ、Redis、数据库持续背压，消息不能在规定时间内恢复。
- 服务反复重启、磁盘快速增长、数据库迁移异常或无法恢复备份。
- 设备断网后丢失超过厂商承诺的缓存，且无明确业务降级方案。

回退顺序：停止该设备发布或隔离设备网络，禁用设备台账，吊销凭据，恢复上一版身份映射/协议/字段映射，确认队列和数据层稳定，保留现场证据并创建缺陷。不得执行 `docker compose down -v`。

最终结论只能是：

- `PASS`：所有强制项通过，可进入受控试运行。
- `FAIL`：已执行但存在不满足项，修复后重新完整验收。
- `BLOCKED`：缺少设备、窗口、协议、权限或其他外部条件，尚不能得出质量结论。

## 13. 常见问题定位

| 现象 | 首查位置 | 常见原因 |
|---|---|---|
| MQTTS 无法连接 | 证书、SNI、设备时钟、RabbitMQ 连接日志 | CA 不信任、名称不符、凭据错误 |
| 连接后立即断开 | Client ID、同账号连接、KeepAlive | Client ID 冲突、设备重连风暴 |
| 能连接但不能发布 | vhost、主题、RabbitMQ topic permission | `productKey/clientId` 与签发身份不一致 |
| gateway 无数据 | 快照错误日志、MQTTS 连接和单帧功能探针 | 未配置身份、目录挂载未升级、设备编码混用 |
| 历史有数据但最新不变 | `bootId/sequence`、Redis、旧周期补传 | 序号规则不符、旧启动周期报文 |
| 前端不刷新 | realtime 队列、WebSocket、浏览器重同步 | 实时消费断开或连接序号缺口 |
| 命令停在 SENT | 下行订阅、设备 ACK、回复主题 | 设备未订阅、回复格式或关联标识错误 |
| 队列持续增长 | consumer、TimescaleDB、Redis、死信 | 数据层故障、解析异常、消费能力不足 |

定位时使用 `X-Request-Id`、消息 `traceId/eventId`、命令 ID 和设备 UUID 关联，不使用明文 secret 作为搜索条件。
