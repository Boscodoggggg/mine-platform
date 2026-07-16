# 11 API、消息与数据库契约

## 1. 契约优先

以下内容属于稳定契约：

- REST 路径、请求/响应 schema 和错误码。
- WebSocket 动作、主题和消息类型。
- RabbitMQ eventType、schemaVersion 和路由键。
- 数据库 migration 和对外导出格式。
- 协议插件 SPI。

实现前先更新契约和契约测试。不能让前后端分别猜字段。

## 2. REST 资源

### 2.1 系统

~~~text
POST   /api/v1/auth/login
POST   /api/v1/auth/logout
GET    /api/v1/auth/me
POST   /api/v1/auth/password
GET    /api/v1/auth/menus

GET             /api/v1/system/users
POST            /api/v1/system/users
PUT             /api/v1/system/users/{id}
PUT             /api/v1/system/users/{id}/roles
POST            /api/v1/system/users/{id}/password-reset

GET             /api/v1/system/roles
POST            /api/v1/system/roles
PUT             /api/v1/system/roles/{id}
DELETE          /api/v1/system/roles/{id}
PUT             /api/v1/system/roles/{id}/menus
GET             /api/v1/system/menus/tree

GET             /api/v1/operation-logs
GET             /api/v1/login-logs
~~~

访问管理成功响应遵循标准 REST：分页和详情直接返回 DTO，创建返回 `201 Created` 与 `Location`，无响应体写操作返回 `204 No Content`。不使用额外 `Result<T>` 包裹。所有错误统一返回本章第 3 节的错误对象。

访问管理约束：

- 用户更新和角色更新必须携带 `version`，过期版本返回 `409 / SYSTEM-409-003`。
- 用户只停用，不提供物理删除接口；超级管理员不能被停用、重置密码或修改角色。
- 临时密码为 12～72 UTF-8 字节，创建和重置后 `passwordChangeRequired=true`，重置会注销旧会话。
- 角色 `code` 创建后不可修改；仍分配给用户的角色删除返回 `409 / SYSTEM-409-004`。
- 菜单树只读，节点由 Flyway 维护；角色授权只接受真实存在且启用的节点。
- 列表页码从 1 开始，`size` 范围为 1～100。

`GET /api/v1/auth/menus` 响应：

~~~json
{
  "roles": ["DEVICE_OPERATOR"],
  "permissions": ["device:device:list"],
  "menus": [
    {
      "id": "0198aef6-2e80-7000-8000-000000000101",
      "parentId": null,
      "name": "设备管理",
      "type": "DIRECTORY",
      "path": "/device",
      "componentKey": null,
      "permission": null,
      "icon": "mdi:factory",
      "sortOrder": 10,
      "visible": true,
      "children": [
        {
          "id": "0198aef6-2e80-7000-8000-000000000102",
          "parentId": "0198aef6-2e80-7000-8000-000000000101",
          "name": "设备台账",
          "type": "MENU",
          "path": "/devices",
          "componentKey": "device-list",
          "permission": null,
          "icon": "mdi:truck",
          "sortOrder": 20,
          "visible": true,
          "children": []
        }
      ]
    }
  ]
}
~~~

约束：

- 普通用户仅返回启用角色直接授权的节点，并自动补齐这些节点的启用祖先。
- 超级管理员返回全部启用节点；角色和权限数组可包含超级管理员通配值。
- DIRECTORY 只组织导航，MENU 才能生成页面路由，BUTTON 只表达操作权限。
- `componentKey` 是前端白名单键，不是文件路径；未知键由前端安全降级。
- 父节点未授权但子按钮已授权时，父目录和页面仍必须返回，否则前端无法形成可达导航。

### 2.2 设备与模型

~~~text
GET             /api/v1/products
POST            /api/v1/products
PUT             /api/v1/products/{id}
POST            /api/v1/products/{id}/archive

GET             /api/v1/devices
POST            /api/v1/devices
PUT             /api/v1/devices/{id}
POST            /api/v1/devices/{id}/archive
POST            /api/v1/devices/{id}/credentials
POST            /api/v1/device-credentials/{id}/rotate
DELETE          /api/v1/device-credentials/{id}
GET             /api/v1/devices/{id}/latest
GET             /api/v1/devices/{id}/telemetry

GET/POST         /api/v1/thing-models
POST             /api/v1/thing-models/{id}/versions
POST             /api/v1/thing-model-versions/{id}/validate
~~~

设备遥测查询约束：

- 两个接口均需要 `device:telemetry:view`；该权限由隐藏的“设备监控”菜单节点承载，页面交付后再切换为可见。
- latest 必须显式传入 1 至 100 个 `propertyIds`，优先读取 Redis；缓存缺失时按设备和属性合并并发回源 TimescaleDB，回填不得覆盖并发到达的新值。
- history 每次只查询一个数值属性，时间范围采用 `[from, to)`；`RAW` 最多 7 天，`AVG/MIN/MAX` 最多 366 天。
- history 首页返回已提交事件的 `watermark` 摄取水位，后续页必须原样传回；历史查询使用
  `ingest_id <= watermark` 固定数据集，确保弱网迟到、MQ 积压和数据库重试期间跨请求分页稳定；客户端传入的
  水位不得超过服务端当前已提交水位。
- 聚合查询必须传 1 分钟至 24 小时且为整秒精度的 ISO-8601 `bucket`，单次最多形成 10000 个时间桶；每页最多 1000 条，分页 offset 不得超过 1000000。
- 聚合质量取桶内最差值，响应必须同时给出样本数；完整契约见 `docs/openapi/mine-api.yaml`。

产品和设备管理约束：

- `productKey` 和 `deviceCode` 在单矿区数据库内永久唯一，归档后也不复用。
- 产品编码、设备编码和设备所属产品创建后不可修改，避免遥测、命令和审计引用漂移。
- 产品与设备更新、归档必须携带 `version`；过期版本返回 `409 / DEVICE-409-003`。
- 归档是不可逆的软删除，已归档记录仍可按状态查询；产品下存在未归档设备时不能归档产品。
- 只有 `ACTIVE` 产品可以创建设备。
- 产品和设备成功写操作统一写入 `sys_operation_log` 的 `DEVICE_MANAGEMENT` 模块。
- 设备列表与详情响应不返回凭据、密钥散列或密钥提示。

设备 MQTT 凭据约束：

- `POST /devices/{id}/credentials` 需要 `device:credential:create`；只有 `ENABLED` 设备可以签发，且每台设备同时只允许一个 `ACTIVE` 凭据。
- `POST /device-credentials/{id}/rotate` 需要 `device:credential:rotate`，请求体为 `{"version":0}`；轮换成功后平台主动关闭该用户的现有连接，旧密码不能再次建立 MQTT 连接。
- `DELETE /device-credentials/{id}?version=0` 需要 `device:credential:revoke`；吊销会同时删除 RabbitMQ 设备用户。
- 签发和轮换响应包含 `username`、`clientId`、`secret`、`topicRoot`、`upTopic`、`downTopic` 和 `version`。`secret` 只在当次成功响应返回，平台不提供再次查看接口。
- `username` 与 `clientId` 使用平台生成的安全不透明标识，不直接复用第三方 `deviceCode`；这允许设备编码包含冒号、点号等厂商字符，同时避免污染 RabbitMQ 权限正则。
- 设备只可发布 `mine/v1/{productKey}/{clientId}/up/{messageType}`，只可订阅 `mine/v1/{productKey}/{clientId}/down/{messageType}`；RabbitMQ 在 `/mine-device` vhost 同时执行资源权限与 topic permission 双重隔离。
- 业务库只保存 BCrypt 散列和末尾 8 位提示，响应、审计和异常不得记录明文密钥。RabbitMQ 管理员凭据通过只读 secret 文件挂载，不进入环境变量或数据库。
- 数据库事务失败时会尽力删除刚创建的 RabbitMQ 用户；管理接口失败会回滚数据库变更。数据库提交与 Broker 管理接口无法形成分布式原子事务，后续需由凭据对账任务发现并修复极低概率的提交窗口不一致。

### 2.3 协议与策略

~~~text
GET              /api/v1/device-models/products/{productId}/configuration
POST             /api/v1/device-models/products/{productId}/model-drafts
PUT              /api/v1/device-models/model-versions/{id}/fields
POST             /api/v1/device-models/model-versions/{id}/publish
POST             /api/v1/device-models/products/{productId}/protocol-drafts
PUT              /api/v1/device-models/protocol-versions/{id}/fields
POST             /api/v1/device-models/protocol-versions/{id}/publish

GET/PUT          /api/v1/protocol-mapping-versions/{id}/samples
POST             /api/v1/protocol-mapping-versions/{id}/test

GET/POST         /api/v1/config-releases
GET              /api/v1/config-releases/{id}
POST             /api/v1/config-releases/{id}/rollback

GET              /internal/v1/config-releases/{id}
POST             /internal/v1/config-releases/{id}/nodes/{nodeId}
~~~

样本、测试、发布、回滚和查看发布历史分别使用 `device:protocol:sample:view`、`device:protocol:sample:edit`、`device:protocol:test`、`device:configuration:publish`、`device:configuration:rollback`、`device:configuration:view` 权限。发布和回滚必须携带 `Idempotency-Key`；内部接口不使用用户会话，必须携带从只读 secret 文件加载的 `X-Mine-Service-Token`，且响应不包含任何凭据。

### 2.4 矿山业务

~~~text
GET/POST/PATCH  /api/v1/vehicles
GET/POST/PATCH  /api/v1/persons
GET/POST        /api/v1/bindings
POST            /api/v1/bindings/{id}/close
GET/POST/PATCH  /api/v1/routes
GET/POST/PATCH  /api/v1/waypoints
GET/POST/PATCH  /api/v1/geofences
GET/POST        /api/v1/work-orders
GET             /api/v1/operation-records
GET             /api/v1/location-points
GET             /api/v1/tracks
~~~

### 2.5 命令与告警

~~~text
GET/POST  /api/v1/commands
GET       /api/v1/commands/{id}
POST      /api/v1/commands/{id}/cancel
GET       /api/v1/command-templates

GET       /api/v1/alarms
POST      /api/v1/alarms/{id}/acknowledgements
POST      /api/v1/alarms/{id}/close
~~~

## 3. 通用请求/响应

分页响应：

~~~json
{
  "items": [],
  "page": 1,
  "size": 20,
  "total": 0
}
~~~

错误：

~~~json
{
  "code": "STRATEGY-409-001",
  "message": "设备组策略存在同优先级冲突",
  "traceId": "abc",
  "timestamp": "2026-07-14T08:00:00Z",
  "details": {
    "violations": [
      {
        "field": "offlineTimeout",
        "reason": "group-a 与 group-b 的值不同"
      }
    ]
  }
}
~~~

`details` 始终是 JSON 对象；字段校验错误统一放入 `details.violations` 数组，因此一次请求可以返回多个 `{field, reason}`。

写操作接受 X-Request-Id；命令和发布接受 Idempotency-Key。

## 4. WebSocket 契约

统一端点为 `GET /api/v1/realtime`，由 Nginx 以 WSS 反向代理。浏览器握手不携带 Sa-Token 请求头，连接建立后必须首先发送 `AUTH`：

~~~json
{
  "action": "AUTH",
  "requestId": "rt-1",
  "token": "sa-token-value"
}
~~~

一期客户端动作：

- `AUTH`：连接建立后的必选鉴权动作，当前用户必须具有 `device:telemetry:view`。
- `SUBSCRIBE`：订阅 `DEVICE_LATEST` 设备属性最新值。
- `UNSUBSCRIBE`：释放设备全部或指定属性订阅。
- `PING`：应用层心跳，只返回 `PONG`，不查询数据库。
- `RESYNC`：发现连接内 `sequence` 缺口后，对当前全部订阅重新读取快照。

订阅示例：

~~~json
{
  "action": "SUBSCRIBE",
  "requestId": "rt-2",
  "topics": [
    {
      "type": "DEVICE_LATEST",
      "ids": ["0198aef6-2e80-7000-8000-000000000101"],
      "properties": ["speed", "engine.rpm"]
    }
  ]
}
~~~

服务端：

- `ACK`：动作接收确认，原样携带 `requestId` 和 `action`。
- `SNAPSHOT`：首次订阅或 `RESYNC` 的当前全量快照。
- `DATA`：已完成 TimescaleDB、持久最新值和 Redis 提交后的增量。
- `ERROR`：错误码和中文说明。
- `PONG`：心跳回复。

除客户端请求中的 `AUTH.token` 外，服务端消息都带连接内单调递增 `sequence` 和 `serverTime`。`sequence` 只用于检测本连接断档，不承诺跨重连连续，也不代表设备原始序号。重连后重新 `AUTH` 和 `SUBSCRIBE`，服务端先返回 `SNAPSHOT` 再继续增量。

浏览器默认每 25 秒发送一次 `PING`，10 秒内未收到 `PONG` 时主动关闭半开连接并进入指数退避重连。`PING` 只重新校验令牌并返回心跳，不读取 Redis、业务库或时序库。

正常增量不查询业务表或时序表：`mine-telemetry` 提交遥测后发布 RabbitMQ 实时事件，每个 `mine-server` 实例通过自己的独占临时队列消费并只分发给本机订阅。只有首次订阅和明确的 `RESYNC` 才读取最新值快照。

默认单连接限制为 64 个订阅、128 台设备、1024 个属性和 64 KiB 客户端文本帧。超限返回 `REALTIME-LIMIT-EXCEEDED`。后续 LOCATION、告警和命令状态主题新增时只能向前兼容扩展，不能改变 `DEVICE_LATEST` 语义。

## 5. 内部事件目录

| eventType | 生产者 | 消费者 | 幂等键 |
|---|---|---|---|
| telemetry.normalized | gateway | telemetry | 设备+boot+sequence+类型 |
| location.normalized | gateway | telemetry | 标签+boot+sequence |
| device.online | gateway/telemetry | server | 设备+会话 |
| device.offline | telemetry | server | 设备+离线周期 |
| alarm.raised | telemetry | server | 规则+设备+活动周期 |
| alarm.recovered | telemetry | server | alarmId |
| command.dispatch.requested | server | gateway | eventId，业务侧 commandId |
| command.status.changed | gateway | server/web | eventId，业务侧 commandId+状态 |
| config.release.created | server | gateway | eventId，业务侧 releaseId |
| operation.completed | telemetry/server | server | operationId |

`config.release.created` 由配置发布事务写入 `sys_outbox_event`，事件只包含 releaseId、packageId、productId、releaseSequence 和 contentHash。Gateway 通过内部 HTTP 拉取完整快照并回报节点状态，完整快照和节点结果不经过 RabbitMQ 事件载荷。

## 6. 数据库实例

### 6.1 业务 PostgreSQL

保存：

- 用户权限。
- 产品设备。
- 物模型、协议、策略和发布。
- 车辆、人员、路线、绑定和运营。
- 命令、告警和审计。
- outbox/inbox。

### 6.2 TimescaleDB

保存：

- 通用属性历史。
- 高频 UWB 位置。
- 遥测事件和聚合。

开发环境可以同实例不同 database/schema；生产为独立实例和资源配额。

## 7. 系统表

### sys_user

~~~text
id UUID PK
username VARCHAR UNIQUE
password_hash VARCHAR
display_name VARCHAR
status VARCHAR
password_change_required BOOLEAN
last_login_at TIMESTAMPTZ
created_at/created_by/updated_at/updated_by
version BIGINT
~~~

### sys_role

~~~text
id UUID PK
code VARCHAR UNIQUE
name VARCHAR
status VARCHAR
description VARCHAR
created_at/created_by/updated_at/updated_by
version BIGINT
~~~

### sys_menu

~~~text
id UUID PK
parent_id UUID NULL
name VARCHAR
type VARCHAR        -- DIRECTORY/MENU/BUTTON
path VARCHAR NULL
component_key VARCHAR NULL
permission VARCHAR NULL
icon VARCHAR NULL
sort_order INTEGER
visible BOOLEAN
status VARCHAR
created_at/created_by/updated_at/updated_by
version BIGINT
~~~

### 关联

~~~text
sys_user_role(user_id, role_id) UNIQUE
sys_role_menu(role_id, menu_id) UNIQUE
~~~

另外：

- sys_login_log。
- sys_operation_log。

## 8. 设备与模型表

~~~text
dev_product
dev_device
dev_device_group
dev_device_group_member
dev_device_credential
dev_device_status_history

model_definition
model_version
model_property
model_event
model_command
model_display_schema
~~~

关键约束：

- product_key 唯一且发布使用后不可随意改变。
- device_code 在矿区唯一。
- credential secret 不以可逆明文存业务库。
- model_version(definition_id, version_no) 唯一。
- property identifier 在单个模型版本内唯一。

## 9. 协议和策略表

~~~text
protocol_definition
protocol_version
protocol_message
protocol_field
protocol_sample
protocol_test_run

strategy_definition
strategy_version
strategy_scope

config_package
config_package_item
config_release
config_release_target
config_node_status
~~~

建议把大型协议配置 JSON 存 jsonb，同时将可查询元数据关系化。不能只存一坨 JSON 而无法建立版本、样本和影响关系。

发布版本包含：

- version_no。
- schema_version。
- content_hash。
- status。
- created_by/created_at。
- published_by/published_at。
- change_reason。

Phase 2 已落地表及关键语义：

- `dev_protocol_sample`：协议版本内唯一命名的脱敏原始报文和期望结果，最多 200 条。
- `dev_protocol_test_run`：一次不可变回放的定义哈希、通过/失败计数和逐条结果。
- `dev_config_package`：产品内单调包序号、模型/映射版本引用、规范化哈希和完整非敏感 JSONB 快照。
- `dev_config_release`：产品内单调发布序号、发布/回滚动作、原因和管理员幂等键。
- `dev_config_release_target`：产品、设备组或设备目标；当前公开发布 API 先落产品作用域。
- `dev_config_node_status`：节点维度的 `PENDING/LOADED/FAILED`、实际哈希、脱敏消息和加载时间。

所有新增表和字段均通过 `COMMENT ON TABLE`、`COMMENT ON COLUMN` 提供中文注释；`DatabaseMigrationIT` 会读取 PostgreSQL 元数据校验注释不缺失。配置包和发布表由触发器保护为不可变，产品内序号、哈希格式、回滚引用、状态与加载时间等均有数据库约束。

## 10. 矿山业务表

~~~text
biz_vehicle
biz_person
biz_driver_profile

biz_vehicle_device_binding
biz_vehicle_tag_binding
biz_person_tag_binding
biz_person_vehicle_binding

biz_route
biz_route_version
biz_waypoint
biz_route_waypoint
biz_geofence

biz_shift_definition
biz_shift_instance
biz_work_order
biz_operation_record
biz_operation_metric

cmd_template
cmd_instance
cmd_attempt
cmd_reply

alarm_event
alarm_acknowledgement
~~~

绑定表必须有排除约束或事务校验，禁止同一对象在相同类型和时间范围重叠。

## 11. 消息一致性表

### sys_outbox_event

~~~text
id UUID PK
aggregate_type VARCHAR
aggregate_id UUID
event_type VARCHAR
schema_version INTEGER
payload JSONB
status VARCHAR
attempts INTEGER
next_attempt_at TIMESTAMPTZ
locked_by VARCHAR NULL
locked_at TIMESTAMPTZ NULL
lease_expires_at TIMESTAMPTZ NULL
created_at TIMESTAMPTZ
published_at TIMESTAMPTZ NULL
last_error VARCHAR NULL
~~~

索引：

- status + next_attempt_at。
- PUBLISHING + lease_expires_at，用于 relay 崩溃后的超时重新认领。
- aggregate_type + aggregate_id。
- published_at 用于归档。

Outbox 投递约束：

- 业务事务写入事件时同时发送提交后唤醒通知，通知只携带 eventId，不携带大型 payload。
- relay 被通知后按主键精确认领；发送失败只重试已知 eventId，不固定间隔扫描 PENDING 全表。
- 服务启动或数据库重连后执行一次有界恢复，必须命中 `status + next_attempt_at` 索引并限制批量。
- `locked_by + lease_expires_at` 只用于实例崩溃后的接管，不允许用分布式锁替代数据库状态机。
- relay 只允许精确白名单事件：`COMMAND/command.dispatch.requested` 与 `CONFIG_RELEASE/config.release.created`；配置事件路由到 `mine.config.x`，Gateway 使用手工 ACK。
- Gateway 仅在快照匹配、编译激活、本地 last-known-good 持久化和节点 `LOADED` 回报全部成功后 ACK；HTTP、磁盘或状态回报瞬时失败 NACK 重投，非法事件或永久损坏快照拒绝并进入死信。

### sys_inbox_event

~~~text
consumer_name VARCHAR
event_id UUID
processed_at TIMESTAMPTZ
result VARCHAR
PRIMARY KEY(consumer_name, event_id)
~~~

### cmd_instance

每次下行意图创建一条不可复用的命令实例，核心字段如下：

~~~text
id UUID PK
device_id UUID FK
template_key VARCHAR
template_version INTEGER
parameters JSONB
risk_level VARCHAR
status VARCHAR
ttl_seconds INTEGER
timeout_seconds INTEGER
retry_policy JSONB
idempotency_key VARCHAR
protocol_version VARCHAR NULL
config_release_id UUID NULL
created_by UUID FK
queued_at/sent_at/device_received_at/completed_at TIMESTAMPTZ NULL
expires_at TIMESTAMPTZ
last_error_code VARCHAR NULL
reply JSONB NULL
version BIGINT
UNIQUE(created_by, idempotency_key)
~~~

一期只发布 `QUERY_STATUS` 低风险示例模板。创建命令与 `command.dispatch.requested` Outbox 事件必须处于同一事务；
重复幂等键直接返回原命令，不能再写第二条出站事件。状态转换由后端白名单控制：
`CREATED -> QUEUED/CANCELLED`、`QUEUED -> SENT/EXPIRED`、`SENT -> DEVICE_RECEIVED/TIMEOUT`、
`DEVICE_RECEIVED -> SUCCEEDED/REFUSED/FAILED/TIMEOUT`，终态不得回退。

状态事件消费采用 `sys_inbox_event + cmd_instance FOR UPDATE` 的单事务模型，事务提交后才确认 RabbitMQ 消息。
同一 `eventId` 重投不会重复推进版本；不同事件乱序到达时按业务证据处理：设备终态可从 `QUEUED` 直接补全发送和设备接收
时间线，迟到的 `SENT` 或 `TIMEOUT` 不能覆盖终态。该机制完全由消息到达触发，不允许新增 Inbox 或命令表定时扫描任务。

## 12. Timescale 表

示意 DDL：

~~~sql
CREATE TABLE telemetry_numeric (
    time              TIMESTAMPTZ NOT NULL,
    device_id         UUID        NOT NULL,
    product_key       VARCHAR(64) NOT NULL,
    property_id       VARCHAR(128) NOT NULL,
    value_double      DOUBLE PRECISION,
    quality           SMALLINT    NOT NULL,
    boot_id           VARCHAR(64),
    sequence          BIGINT,
    event_id          UUID        NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL
);

SELECT create_hypertable('telemetry_numeric', by_range('time'));

CREATE INDEX idx_tn_device_property_time
    ON telemetry_numeric(device_id, property_id, time DESC);
~~~

位置：

~~~sql
CREATE TABLE location_point (
    time                  TIMESTAMPTZ NOT NULL,
    device_id             UUID,
    tag_id                UUID        NOT NULL,
    x                     DOUBLE PRECISION NOT NULL,
    y                     DOUBLE PRECISION NOT NULL,
    z                     DOUBLE PRECISION,
    speed                 DOUBLE PRECISION,
    heading               DOUBLE PRECISION,
    quality               SMALLINT NOT NULL,
    coordinate_system_id  UUID NOT NULL,
    boot_id               VARCHAR(64),
    sequence              BIGINT,
    event_id              UUID NOT NULL,
    received_at           TIMESTAMPTZ NOT NULL
);
~~~

最终 chunk interval、分区和唯一约束必须依据写入基准测试决定，不能照抄示意值。

## 13. 当前值

Redis hash 保存物模型值及独立发生时间。API 查询设备 latest 时：

1. 优先读取 Redis。
2. Redis miss 时查询 Timescale 持久最新值读模型；禁止从包含乱序、退役 boot 数据的历史表按时间猜测。
3. 同步恢复设备当前 boot、最高序号和退役 boot 集合，并以 HSETNX 写回 Redis。
4. 返回 value、occurredAt、receivedAt、quality 和 source。

## 14. 时间和 ID

- 数据库存 UTC TIMESTAMPTZ。
- 矿区时区是站点配置，默认 Asia/Shanghai 只是部署初值。
- UUIDv7 用于主键、eventId、commandId 和 releaseId。
- 外部自然编码如 deviceCode/vehicleCode 单独唯一。
- sequence 使用 64 位整数。

## 15. 删除和归档

- 用户、设备、车辆、人员：归档，不物理删除历史引用。
- 草稿且从未发布的配置允许删除，但记录审计。
- 已发布配置版本不可删除，只能停用或回滚。
- 遥测按保留策略清理。
- 审计按长期策略归档，普通管理员无删除权限。

## 16. Migration 规则

- 文件命名：V{版本}__{中文或英文说明}.sql。
- migration 一旦进入正式版本不可修改校验和。
- 大表变更使用 expand/contract。
- 新列先 nullable/有默认兼容，再发布应用，再收紧约束。
- 索引创建评估锁表和磁盘。
- Timescale extension 升级单独编写 preflight 和恢复说明。
