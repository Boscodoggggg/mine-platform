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
GET/POST         /api/v1/protocols
POST             /api/v1/protocols/{id}/versions
POST             /api/v1/protocol-versions/{id}/test
POST             /api/v1/protocol-versions/{id}/publish

GET/POST         /api/v1/strategies
POST             /api/v1/strategies/{id}/versions
POST             /api/v1/config-packages
POST             /api/v1/config-packages/{id}/validate
POST             /api/v1/config-releases
POST             /api/v1/config-releases/{id}/rollback
GET              /api/v1/config-releases/{id}/nodes
~~~

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

客户端动作：

- AUTH：建立连接后的鉴权（如果握手未完成）。
- SUBSCRIBE：订阅设备、车辆、位置或告警主题。
- UNSUBSCRIBE。
- PING。
- RESYNC：sequence 缺口后请求快照。

服务端：

- ACK：订阅确认。
- SNAPSHOT：当前全量快照。
- DATA：增量。
- STATUS：连接/降级。
- ERROR。
- PONG。

每个连接有订阅数量、设备数量和下行速率限制。

## 5. 内部事件目录

| eventType | 生产者 | 消费者 | 幂等键 |
|---|---|---|---|
| telemetry.normalized | gateway | telemetry | 设备+boot+sequence+类型 |
| location.normalized | gateway | telemetry | 标签+boot+sequence |
| device.online | gateway/telemetry | server | 设备+会话 |
| device.offline | telemetry | server | 设备+离线周期 |
| alarm.raised | telemetry | server | 规则+设备+活动周期 |
| alarm.recovered | telemetry | server | alarmId |
| command.created | server | gateway | commandId |
| command.status.changed | gateway/server | server/web | commandId+状态+attempt |
| config.released | server | gateway/telemetry | releaseId |
| config.node.status | gateway/telemetry | server | releaseId+nodeId+状态 |
| operation.completed | telemetry/server | server | operationId |

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

### sys_inbox_event

~~~text
consumer_name VARCHAR
event_id UUID
processed_at TIMESTAMPTZ
result VARCHAR
PRIMARY KEY(consumer_name, event_id)
~~~

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
2. Redis miss 时查 Timescale 最近值。
3. 写回 Redis。
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
