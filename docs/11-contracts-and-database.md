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
- history 首页返回已提交事件的 `watermark` 摄取水位和当前 `rollupWatermark` 聚合版本水位，后续页必须同时原样传回；历史查询使用
  `ingest_id <= watermark` 固定原始数据集，并为每个桶选择 `rollup_revision <= rollupWatermark` 的最新聚合版本，确保弱网迟到、MQ 积压、数据库重试和后台重算期间跨请求分页稳定；客户端传入的水位不得超过服务端当前已提交水位。
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

GET/POST         /api/v1/device-groups
GET/PUT          /api/v1/device-groups/{id}
GET/PUT          /api/v1/device-groups/{id}/members
POST             /api/v1/device-groups/{id}/archive

GET/POST         /api/v1/strategy-versions
GET/PUT          /api/v1/strategy-versions/{id}
GET/POST         /api/v1/strategy-releases
GET              /api/v1/strategy-releases/{id}
POST             /api/v1/strategy-releases/{id}/rollback
GET              /api/v1/devices/{id}/effective-strategies/{type}
POST             /api/v1/retention-previews
GET              /api/v1/retention-executions?limit=20
GET              /api/v1/aggregation-executions?limit=20
GET/POST         /api/v1/aggregation-recomputations

GET/POST         /api/v1/config-releases
GET              /api/v1/config-releases/{id}
POST             /api/v1/config-releases/{id}/rollback

GET              /internal/v1/config-releases/{id}
POST             /internal/v1/config-releases/{id}/nodes/{nodeId}
POST             /internal/v1/aggregation-recomputations/claim
POST             /internal/v1/aggregation-recomputations/{requestId}/complete
~~~

样本、测试、发布、回滚和查看发布历史分别使用 `device:protocol:sample:view`、`device:protocol:sample:edit`、`device:protocol:test`、`device:configuration:publish`、`device:configuration:rollback`、`device:configuration:view` 权限。发布和回滚必须携带 `Idempotency-Key`；内部接口不使用用户会话，必须携带从只读 secret 文件加载的 `X-Mine-Service-Token`，且响应不包含任何凭据。

设备组查看/维护分别使用 `device:group:view`、`device:group:edit`，策略查看/编辑/发布分别使用 `device:strategy:view`、`device:strategy:edit`、`device:strategy:publish`。设备组成员、优先级和归档变更采用乐观锁；发布和回滚要求 `Idempotency-Key` 与原因。当前策略白名单包含 `ONLINE_STATUS`、`DATA_QUALITY`、`ALARM`、`RETENTION` 和 `AGGREGATION`：前两类按字段继承，`ALARM.rules`、`RETENTION.policy` 和 `AGGREGATION.policy` 分别作为原子集合/对象整组覆盖。有效值查询返回最终字段、作用范围/发布来源、稳定内容哈希和冲突详情。同优先级设备组异值冲突返回 `409 / STRATEGY-409-001`，策略版本、发布事实和审计均不提交。

保留影响预览与执行历史是只读运行接口，要求 `device:strategy:view`。预览请求提交 `strategyVersionId + productId + scopeType + scopeId`，不接受浏览器提供的任意策略对象；服务端按发布后的逐设备最终策略统计，每数据集最多精确统计 100 万行，达到上限时返回 `atLeast=true`。响应的一次性凭证绑定管理员、确切版本内容、作用域、成员、组优先级和最终解析哈希；`RETENTION` 发布与回滚必须提交该凭证，过期、复用或状态变化返回 `RETENTION-409-001`。已有当前 `RETENTION` 组发布时，成员、优先级和归档变更使用独立的设备组变化预览，绑定变化类型、设备组版本、成员差异和目标优先级；最终设备组写事务重新检查 `device:strategy:publish` 并一次消费凭证。独立菜单使用 `governance:retention:view`，授予该菜单时后端自动补齐产品、设备和策略三项只读菜单依赖，不补齐编辑、发布、归档或凭据权限。V28 会为升级前已拥有策略查看菜单的角色补齐相同只读集合。

数值聚合治理查询使用 `governance:aggregation:view`，显式重算使用独立的 `governance:aggregation:execute`。重算必须携带稳定 `Idempotency-Key` 和原因，目标为单设备、单属性、当前启用精确桶规则，时间范围按 UTC 桶边界对齐，最长 31 天且最多 10000 个桶；HTTP `202` 只表示创建或幂等重放 `PENDING` 请求。内部认领/完成接口只接受外部 service token：认领使用有界租约，完成必须同时匹配请求、稳定 executionId 和当前 claim token，旧租约返回冲突且不得覆盖新持有者。

### 2.4 矿山业务

~~~text
GET               /api/v1/mine/context
GET/POST          /api/v1/mine/organizations
PATCH             /api/v1/mine/organizations/{organizationId}
POST              /api/v1/mine/organizations/{organizationId}/archive
GET/POST          /api/v1/mine/organizations/{organizationId}/sites
PATCH             /api/v1/mine/sites/{siteId}
POST              /api/v1/mine/sites/{siteId}/archive
GET/PUT/DELETE    /api/v1/mine/scope-grants

GET/POST          /api/v1/mine/sites/{siteId}/vehicles
PATCH             /api/v1/mine/sites/{siteId}/vehicles/{vehicleId}
POST              /api/v1/mine/sites/{siteId}/vehicles/{vehicleId}/archive
GET/POST          /api/v1/mine/sites/{siteId}/location-tags
PATCH             /api/v1/mine/sites/{siteId}/location-tags/{tagId}
POST              /api/v1/mine/sites/{siteId}/location-tags/{tagId}/archive
GET/POST          /api/v1/mine/sites/{siteId}/coordinate-systems
PATCH             /api/v1/mine/sites/{siteId}/coordinate-systems/{systemId}
POST              /api/v1/mine/sites/{siteId}/coordinate-systems/{systemId}/archive
GET/POST          /api/v1/mine/sites/{siteId}/coordinate-transforms
PATCH             /api/v1/mine/sites/{siteId}/coordinate-transforms/{transformId}
POST              /api/v1/mine/sites/{siteId}/coordinate-transforms/{transformId}/publish

GET/POST          /api/v1/mine/sites/{siteId}/people
GET/PATCH         /api/v1/mine/sites/{siteId}/people/{personId}
POST              /api/v1/mine/sites/{siteId}/people/{personId}/archive
GET/PATCH         /api/v1/mine/sites/{siteId}/people/{personId}/sensitive
GET/POST          /api/v1/mine/sites/{siteId}/people/{personId}/qualifications
POST              /api/v1/mine/sites/{siteId}/people/{personId}/qualifications/{qualificationId}/revoke

POST              /api/v1/mine/sites/{siteId}/bindings/preview
POST              /api/v1/mine/sites/{siteId}/bindings/switch
GET               /api/v1/mine/sites/{siteId}/locations/current
GET               /api/v1/mine/sites/{siteId}/locations/trajectory

GET               /api/v1/mine/sites/{siteId}/spatial-regions/current
GET/POST          /api/v1/mine/sites/{siteId}/waypoints|routes|geofences
GET               /api/v1/mine/sites/{siteId}/{resource}/{id}
POST              /api/v1/mine/sites/{siteId}/{resource}/{id}/archive
GET/POST          /api/v1/mine/sites/{siteId}/{resource}/{id}/versions
GET/PATCH         /api/v1/mine/sites/{siteId}/{resource}/{id}/versions/{versionId}
POST              /api/v1/mine/sites/{siteId}/{resource}/{id}/versions/{versionId}/publish

GET               /api/v1/mine/sites/{siteId}/spatial-events
GET               /api/v1/mine/sites/{siteId}/spatial-events/{eventId}
POST              /api/v1/mine/sites/{siteId}/spatial-events/replays

GET/POST          /api/v1/mine/sites/{siteId}/shift-definitions
GET/PATCH         /api/v1/mine/sites/{siteId}/shift-definitions/{definitionId}
POST              /api/v1/mine/sites/{siteId}/shift-definitions/{definitionId}/archive
GET/POST          /api/v1/mine/sites/{siteId}/shift-instances
GET               /api/v1/mine/sites/{siteId}/shift-instances/{shiftId}
POST              /api/v1/mine/sites/{siteId}/shift-instances/{shiftId}/open|close|cancel

GET/POST          /api/v1/mine/sites/{siteId}/work-orders
GET/PATCH         /api/v1/mine/sites/{siteId}/work-orders/{workOrderId}
POST              /api/v1/mine/sites/{siteId}/work-orders/{workOrderId}/assignments
POST              /api/v1/mine/sites/{siteId}/work-orders/{workOrderId}/release|start|pause|resume|complete|cancel|abort

GET               /api/v1/mine/sites/{siteId}/operation-candidates
POST              /api/v1/mine/sites/{siteId}/operation-candidates/{candidateId}/resolve
GET/POST          /api/v1/mine/sites/{siteId}/operation-records
GET               /api/v1/mine/sites/{siteId}/operation-records/{recordId}
POST              /api/v1/mine/sites/{siteId}/operation-records/{recordId}/corrections
GET               /api/v1/mine/sites/{siteId}/operation-summary

POST              /api/v1/mine/sites/{siteId}/scene-assets
GET               /api/v1/mine/sites/{siteId}/scene-assets
GET               /api/v1/mine/sites/{siteId}/scene-assets/{assetId}
POST              /api/v1/mine/sites/{siteId}/scene-assets/{assetId}/archive
GET               /api/v1/mine/sites/{siteId}/scene-assets/{assetId}/files/{relativePath}

GET/POST          /api/v1/mine/sites/{siteId}/scenes
GET/PATCH         /api/v1/mine/sites/{siteId}/scenes/{sceneId}
POST              /api/v1/mine/sites/{siteId}/scenes/{sceneId}/archive
POST              /api/v1/mine/sites/{siteId}/scenes/{sceneId}/make-default
GET/POST          /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions
POST              /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/next-draft
GET/PATCH         /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}
PUT               /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/layers
POST              /api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/publish
GET               /api/v1/mine/sites/{siteId}/scene-runtime

GET               /api/v1/internal/location-snapshots/latest
GET               /api/v1/internal/location-snapshots/{snapshotId}
~~~

以上路径以 `docs/openapi/mine-api.yaml` 为精确请求/响应契约。业务接口同时校验 Sa-Token 权限和用户组织/矿区数据范围；
前端选择的 `siteId` 不是授权凭据。换绑必须先预览，再用 `Idempotency-Key` 原子执行；标签占用在车辆和人员绑定之间共享
数据库排斥约束。坐标转换发布后不可修改，快照内部接口只接受只读文件加载的服务令牌。

当前位置最多返回 10,000 个标签，默认 5,000；轨迹窗口最长 24 小时、每页最多 5,000 点，使用固定摄入水位和
不透明游标稳定分页。首次 WebSocket 定位订阅先读 REST 快照，增量类型固定为 `realtime.location.updated` v1。

空间资源 `{resource}` 只允许 `waypoints`、`routes`、`geofences`。稳定对象归档，草稿用乐观版本修改，发布版本及其路线引用/
围栏顶点不可变；发布生成 `SPATIAL_CHANGE` 定位快照。空间事件查询需要 `mine:spatial:event:view`，默认过去 24 小时、
最长 31 天、每页最多 200 条，使用固定事件序号水位和不透明游标。重放需要 `mine:spatial:replay`、稳定
`Idempotency-Key`、原因和不超过 31 天的 `[from,to)` 窗口，只异步返回 HTTP 202；请求线程不得扫描历史。

班次、作业单和运营接口固定使用 `mine:operation:view` 读取权限，并按职责拆分 `mine:shift:edit`、`mine:shift:close`、
`mine:work-order:edit`、`mine:work-order:dispatch`、`mine:operation:correct`。开启/关账/取消班次、作业单生命周期动作、候选处置和
记录补录/修正使用持久 `Idempotency-Key`；乐观版本冲突必须刷新后人工决定，不能更换键盲目重放。运营记录查询窗口最长 31 个营业日，
首页返回固定矿区业务水位，后续页原样传回 `watermark` 和不透明 `cursor`；运营汇总只统计当前有效且未作废投影。

场景读取使用 `mine:scene:view`，编辑、资源上传/归档、发布/默认切换分别使用 `mine:scene:edit`、
`mine:scene:asset:upload`、`mine:scene:publish`。GLB 或 3D Tiles ZIP 上传先完成大小、路径、包结构和外链校验，再以
SHA-256 内容寻址并登记逐文件清单；相同矿区摘要幂等复用。文件读取必须先以 `siteId + assetId + relativePath` 命中清单，
只支持同源受权 URL、私有不可变缓存、`ETag/If-None-Match` 和单一字节 `Range`，禁止客户端传入宿主机路径、对象存储键或任意外链。

场景采用稳定对象和草稿/发布版本。草稿修改与图层整体替换使用乐观版本；发布、默认切换、场景归档和资源归档要求持久
`Idempotency-Key`。发布版本、图层和内容摘要不可原地修改，归档资源仍为历史发布版本保持受权可读。`scene-runtime` 只返回摘要、
schema、图层数量和入口均复核成功的完整 `READY` 快照；否则返回无快照的固定 `DEGRADED` 原因。三维存储故障不阻断二维位置、
空间事件、告警和运营 REST 契约。

### 2.5 命令与告警

~~~text
GET/POST  /api/v1/commands
GET       /api/v1/commands/{id}
POST      /api/v1/commands/{id}/cancel
GET       /api/v1/command-templates

GET       /api/v1/alarms
GET       /api/v1/alarms/{id}
POST      /api/v1/alarms/{id}/acknowledgements
POST      /api/v1/alarms/{id}/close
~~~

告警列表需要 `alarm:event:view`，支持 `active`、`closed`、推导状态、等级、设备 UUID/名称或编码关键词、规则编码、触发时间范围和分页。默认告警中心传 `closed=false`，因此恢复但尚未确认或关闭的周期仍可见。详情返回触发/恢复证据、恢复原因、确认/关闭事实以及触发时策略修订、快照和哈希。

确认需要 `alarm:event:ack`，关闭需要 `alarm:event:close`。两个写接口都要求 `Idempotency-Key`、非空说明和当前 `version`；确认可以发生在恢复前或恢复后，关闭只允许已恢复告警。并发版本冲突返回 `409 / ALARM-409-001`，活动告警关闭返回 `409 / ALARM-409-002`。

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

默认单连接限制为 64 个订阅、128 台设备、1024 个属性和 64 KiB 客户端文本帧。超限返回 `REALTIME-LIMIT-EXCEEDED`。
矿区位置使用 `LOCATION`，空间事件使用 `MINE_SITE_SPATIAL_EVENT`；后者同时要求 `mine:spatial:event:view` 和目标矿区数据范围，
事件序号缺口通过 REST 固定水位查询重同步，并与基础位置主题独立降级。后续告警和命令状态主题只能向前兼容扩展，不能改变
`DEVICE_LATEST`、`LOCATION` 或空间事件语义。

## 5. 内部事件目录

| eventType | 生产者 | 消费者 | 幂等键 |
|---|---|---|---|
| telemetry.normalized | gateway | telemetry | 设备+boot+sequence+类型 |
| location.normalized | gateway | telemetry | 标签+boot+sequence |
| realtime.spatial.event.created | telemetry | server/web | 区域版本+标签+事件类型+锚点位置事实 |
| device.online | gateway/telemetry | server | 设备+会话 |
| device.offline | telemetry | server | 设备+离线周期 |
| alarm.raised | telemetry | server | 规则+设备+活动周期 |
| alarm.recovered | telemetry | server | alarmId |
| command.dispatch.requested | server | gateway | eventId，业务侧 commandId |
| command.status.changed | gateway | server/web | eventId，业务侧 commandId+状态 |
| config.release.created | server | gateway | eventId，业务侧 releaseId |
| operation.completed | telemetry/server | server | operationId |

`config.release.created` 由配置发布事务写入 `sys_outbox_event`，事件只包含 releaseId、packageId、productId、releaseSequence 和 contentHash。Gateway 通过内部 HTTP 拉取完整快照并回报节点状态，完整快照和节点结果不经过 RabbitMQ 事件载荷。

`alarm.raised` 与 `alarm.recovered` 的消息 `eventId` 在全部重试中保持不变，业务聚合键为 UUIDv7 `alarmOccurrenceId`。载荷完整携带设备/产品、稳定规则编码和名称快照、来源、等级、属性、策略运行修订/快照/哈希、业务时间和有界结构化证据。Server 使用 `sys_inbox_event` 与告警聚合同事务消费；恢复事件先到时可以独立创建周期，触发事件晚到时只补齐触发证据，任何身份、时间或证据冲突都拒绝而不是覆盖历史。

`realtime.spatial.event.created` 固定 schema v1，`eventId` 为 UUIDv7。载荷包含组织/矿区、稳定区域及不可变发布版本、标签、
发生时车辆或人员、`ENTER/EXIT/DWELL`、锚点位置事实、前后状态、连续确认点、摄入序号、运行修订、发生时间和有界证据。
证据仅允许有限统一坐标、固定几何分类/质量状态、可选固定原因和 `sha256:` 区域摘要。Telemetry 在区域状态、事件和 Outbox
同事务提交后，经 `realtime.spatial.*` 路由发布；Server 只向本机已授权矿区订阅分发。

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
- 空间评估队列、水位、区域状态、进入/离开/停留事件、可靠 Outbox 和历史重放事实。
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

dev_device_group
dev_device_group_membership
dev_strategy_version
dev_strategy_release
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
- `dev_device_group`：产品内稳定编码、显示名称、0～1000 策略优先级、归档状态和乐观锁。
- `dev_device_group_membership`：设备组成员的生效/结束时间区间；当前关系由部分唯一索引约束，设备归档时由数据库自动关闭。
- `dev_strategy_version`：经过策略类型注册表校验的局部字段版本；当前数据库白名单允许 `ONLINE_STATUS`、`DATA_QUALITY`、`ALARM`、`RETENTION`，已发布版本不可修改或删除。
- `dev_strategy_release`：把已发布策略版本应用到产品、设备组或单设备的不可变事实，保存组优先级审计快照、幂等键、动作和回滚引用。
- `dev_retention_preview_evidence`：15 分钟短期一次性预览凭证，保存管理员、版本、作用域、直接发布或设备组变化身份、最终解析哈希、统计/过期时间和消费事实；V29 创建并完整注释。

所有新增表和字段均通过 `COMMENT ON TABLE`、`COMMENT ON COLUMN` 提供中文注释；`DatabaseMigrationIT` 会读取 PostgreSQL 元数据校验注释不缺失。配置包和发布表由触发器保护为不可变，产品内序号、哈希格式、回滚引用、状态与加载时间等均有数据库约束。成员关系触发器再次校验设备/组产品一致性和生命周期；策略发布触发器再次校验版本、产品、策略类型与多态作用范围一致性。

## 10. 矿山业务表

已落地矿区管理表使用统一 `mine_` 前缀：

~~~text
mine_organization
mine_site
mine_user_scope_grant

mine_vehicle
mine_location_tag
mine_person
mine_driver_qualification
mine_person_site_assignment

mine_vehicle_device_binding
mine_location_tag_binding_claim
mine_vehicle_tag_binding
mine_person_tag_binding
mine_person_vehicle_binding
mine_binding_switch_operation

mine_coordinate_system
mine_coordinate_transform_version
mine_location_runtime_snapshot
mine_location_runtime_snapshot_item

mine_waypoint
mine_waypoint_version
mine_route
mine_route_version
mine_route_version_waypoint
mine_geofence
mine_geofence_version
mine_geofence_vertex

mine_shift_definition
mine_shift_instance
mine_work_order
mine_work_order_assignment
mine_work_order_status_history
mine_operation_spatial_event_queue
mine_operation_runtime_state
mine_operation_candidate
mine_operation_record
mine_operation_evidence
mine_operation_correction
mine_operation_effective_record
mine_operation_outbox
mine_operation_site_sequence
mine_operation_action_idempotency

mine_scene_asset
mine_scene_asset_file
mine_scene
mine_scene_version
mine_scene_layer
mine_scene_action_idempotency

cmd_template
cmd_instance
cmd_attempt
cmd_reply

alarm_event
alarm_acknowledgement
~~~

空间控制面八张表采用稳定对象与 `DRAFT/PUBLISHED` 版本，数据库约束和触发器保护矿区/统一坐标一致性、发布时间范围、
路线引用顺序、多边形拓扑、摘要格式和发布后不可变。V39 创建表，V40 发布菜单权限，V41 扩展定位快照并保护被引用坐标系和版本。

生产运营表由 V43～V49 固化。V43 创建班次、作业单、调度、事件队列、运行态、候选、不可变记录/证据、追加修正、有效投影和
Outbox；V44 增加用户动作幂等账本与精确权限；V45 加固生命周期和不可变约束；V46 增加按车辆/未解析标签的事件顺序索引；
V47 创建发生时作业状态历史；V48 创建矿区连续业务序号；V49 把菜单组件键升级为 `mine-production-operations`。
自动识别消费 `realtime.spatial.event.created` v1，运营变更通过同事务 Outbox 发布 `realtime.operation.updated` v1；前端订阅
`MINE_SITE_OPERATION`，发现连续业务序号缺口后回查固定水位 REST。

场景控制面由 V50、V50.1、V50.2 和 V51 固化。V50 创建内容寻址资源、逐文件清单、稳定场景、版本和有序图层，使用数据库约束与触发器保护
组织/矿区/坐标一致性、摘要、入口、文件数、单草稿/单当前发布、默认唯一、图层类型/顺序/上限和发布不可变；V50.1 增加资源
乐观版本，V50.2 增加场景草稿乐观版本和发布/默认/归档动作幂等账本；V51 发布菜单与四类精确权限。服务端把全部图层、默认相机和
二维视口纳入发布摘要，数据库触发器保护发布后版本和子事实。对象存储只保存 `scene/{上传摘要}/{安全相对路径}` 内容，数据库清单仍是受权和完整性事实；
归档不是物理删除依据，未登记对象的清理必须经过宽限期、数据库/对象清单对账、备份和双人复核。

`alarm_event` 以告警周期为聚合根，保存设备与规则快照、策略运行证据，以及相互正交的触发、恢复、首次确认和关闭事实。推导状态包括 `ACTIVE_UNACKNOWLEDGED`、`ACTIVE_ACKNOWLEDGED`、`RECOVERED_UNACKNOWLEDGED`、`RECOVERED_ACKNOWLEDGED`、`CLOSED`；数据库约束保证关闭一定晚于恢复。`alarm_acknowledgement` 是每个周期唯一的首次确认事实，保存操作人、说明、幂等键和时间。V26 为两表及全部字段提供中文注释，`DatabaseMigrationIT` 校验完整性、索引、约束和菜单权限节点。

通知控制面由 V53～V62 固化：`notification_channel` 保存 Provider、非敏感配置和独立密钥加密凭据；`notification_recipient_group` 与 `notification_recipient` 保存启用边界、加密触达目标和不可逆查重摘要；`notification_template` 保存渠道、告警生命周期和变量白名单约束；`notification_policy`、`notification_policy_version` 与 `notification_policy_step` 保存稳定策略、有序步骤、草稿和不可变发布快照；`notification_secret_key_guard` 在启动时验证数据库与部署密钥身份。V62 把外部联系人契约统一为 `EXTERNAL_CONTACT` 并迁移旧 `EXTERNAL` 数据。全部表和字段都有中文注释，迁移集成测试同时验证约束、权限和升级兼容。

Telemetry 数据库另保存：

- `telemetry_alarm_policy`：每台设备最后正确的已编译告警策略镜像。
- `telemetry_alarm_runtime_state`：设备+规则唯一的 `NORMAL/FIRING_PENDING/ACTIVE/RECOVERING_PENDING/COOLDOWN` 状态及策略/规则指纹。
- `telemetry_outbox_event`：只允许 `alarm.raised` 和 `alarm.recovered` 的可靠发布事实，包含租约、退避、发布确认和死信状态。

这些表与字段同样由迁移写入中文注释并由真实 TimescaleDB 集成测试校验。禁止通过手工更新运行状态、Outbox 或告警周期修复业务；策略回退必须创建更高发布，人工处置必须走受权 API。

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
- Gateway 仅在快照匹配、编译激活、本地 last-known-good 持久化和节点 `LOADED` 回报全部成功后 ACK；HTTP、磁盘或状态回报瞬时失败 NACK 重投，非法事件直接拒绝。永久损坏快照必须先回报脱敏且有界的节点 `FAILED` 证据再拒绝；失败证据无法回报时继续 NACK 重投。

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
    time                         TIMESTAMPTZ NOT NULL,
    organization_id              UUID,
    site_id                      UUID,
    source_device_id             UUID,
    tag_id                       UUID NOT NULL,
    source_coordinate_system_id  UUID,
    source_x                     DOUBLE PRECISION,
    source_y                     DOUBLE PRECISION,
    source_z                     DOUBLE PRECISION,
    target_coordinate_system_id  UUID NOT NULL,
    local_x                      DOUBLE PRECISION NOT NULL,
    local_y                      DOUBLE PRECISION NOT NULL,
    local_z                      DOUBLE PRECISION,
    transform_version_id         UUID,
    binding_type                 VARCHAR(32),
    binding_id                   UUID,
    vehicle_id                   UUID,
    person_id                    UUID,
    speed_meters_per_second      DOUBLE PRECISION,
    heading_degrees              DOUBLE PRECISION,
    accuracy_meters              DOUBLE PRECISION,
    quality_status               VARCHAR(16) NOT NULL,
    quality_reason               VARCHAR(128),
    boot_id                      TEXT,
    sequence                     BIGINT,
    event_id                     UUID NOT NULL,
    ingest_sequence              BIGINT NOT NULL,
    received_at                  TIMESTAMPTZ NOT NULL
);
~~~

V21～V24 将早期 hypertable 原位升级为双坐标事实；为兼容无法补齐快照证据的既有分区，新增运行字段保持物理可空，
但 `ck_location_point_runtime_fact` 以 `NOT VALID` 约束强制迁移后的新增/更新事实必须具备组织、矿区、可信设备、标签、
来源/目标坐标系、转换版本、启动周期和序号。模型允许活动但未绑定对象的标签级事实，并补充矿区/标签/车辆/人员/轨迹查询索引。
`(source_device_id, boot_id, sequence, time)` 和事件标识承担设备周期与事件幂等，`ingest_sequence` 为当前位置、实时增量和
稳定分页提供平台顺序。最终 chunk interval、压缩、分区和容量阈值仍必须依据现场写入基准测试决定。

定位运行同时使用：

- 管理 PostgreSQL `mine_organization`、`mine_site`、`mine_user_scope_grant`：客户与矿区数据范围。
- 管理 PostgreSQL `mine_vehicle`、`mine_location_tag`、`mine_person`、`mine_driver_qualification`：矿区资产和人员主数据。
- 管理 PostgreSQL `mine_coordinate_system`、`mine_coordinate_transform_version`：来源/统一双坐标和不可变转换版本。
- 管理 PostgreSQL `mine_*_binding`、`mine_location_tag_binding_claim`、`mine_binding_switch_operation`：发生时绑定、跨对象标签排斥和幂等换绑。
- 管理 PostgreSQL `mine_location_runtime_snapshot`、`mine_location_runtime_snapshot_item`：不含敏感字段的不可变矿区完整快照。
- TimescaleDB `location_snapshot_stage`、`location_runtime_revision`：整包校验后原子激活的最后正确运行修订。
- TimescaleDB `location_inbox`、`location_point`、`location_realtime_outbox`：幂等凭据、双坐标事实和同事务实时事件。
- TimescaleDB `location_quarantine`：固定原因、有限证据和最长 30 天到期的隔离记录。
- TimescaleDB `location_current_rebuild_watermark`：Redis 丢失后的有界重建顺序水位。

上述新增表和每个字段均通过 `COMMENT ON TABLE/COLUMN` 提供中文注释，PostgreSQL/TimescaleDB 集成测试校验注释、
排斥约束、不可变快照、幂等、边界、发生时绑定、查询水位和有界恢复。模拟与运维边界见
[矿区定位运行与模拟验收手册](runbooks/mine-location-runtime.md)。

空间事件运行新增：

- `spatial_evaluation_queue`、`spatial_subject_watermark`：持久入队、事件时间排序、允许迟到、空闲刷新、租约和固定跳过原因。
- `spatial_zone_state`：区域版本与标签之间可恢复的在线状态机；边界保持、连续确认和停留单次发出。
- `spatial_event`、`spatial_event_sequence_watermark`、`spatial_event_outbox`：固定提交序号、业务唯一事件和同事务可靠发布。
- `spatial_runtime_snapshot`、`spatial_runtime_snapshot_item`：Telemetry 已激活且可恢复的精确空间运行内容与摘要。
- `spatial_replay_request`、`spatial_replay_subject_state`：受控异步重放、冻结源水位/区域快照和独立状态。
- `spatial_replay_active_zone_snapshot`：只向最小权限重放登记账号暴露当前活动途径点/围栏版本的安全视图。

V25 建立基础运行表，V26 增加租约 fencing 与空间运行快照，V27 增加固定查询序号、重放隔离和最小权限视图，V28 增加
空间事件 Outbox `DEAD` 状态部分索引。所有表和字段都有中文注释，TimescaleDB 集成测试校验升级、约束、不可变证据、旧租约拒绝、查询水位和
重放幂等。运行、模拟、指标、告警与回退见
[矿区路线、围栏与空间事件运行手册](runbooks/mine-spatial-event-runtime.md)。

数据保留运行新增：

- `telemetry_retention_policy`：单设备最后正确 `RETENTION.policy` 镜像，保存运行修订、快照、规范化 JSON、内容哈希，并用生成列暴露启用状态、等级和两个期限。
- `telemetry_retention_execution`：不可删除的单轮执行事实，保存实例、状态、启用策略数、两个数据集删除量、批次数、数据库开始/完成时间和脱敏错误；触发器只允许运行进度单向增加及 `RUNNING` 收敛到终态。

V15 创建策略镜像并扩展运行修订白名单，V16 创建执行事实和候选索引。所有表和字段使用中文注释，TimescaleDB 集成测试校验注释完整、约束、索引、终态保护及有界删除边界。

数值聚合运行新增：

- 业务 PostgreSQL `dev_aggregation_recompute_request`：显式重算控制面事实，保存目标、UTC 范围、原因、操作人幂等键、状态、稳定执行标识、当前认领实例/token/租约、尝试次数、完成时间和脱敏错误。
- TimescaleDB `telemetry_aggregation_policy`：单设备最后正确 `AGGREGATION.policy` 镜像，保存运行修订、快照、规范化 JSON、内容哈希，并用生成列暴露启用状态和修正窗口。
- TimescaleDB `telemetry_aggregation_execution`：不可删除的常规或显式重算执行事实，保存固定源摄取水位、策略/规则数、桶产量、聚合水位、迟到量；重算还保存请求、目标范围和精确策略证据。
- TimescaleDB `telemetry_numeric_rollup`：按设备、属性、固定桶宽、UTC 桶起点和全局 `rollup_revision` 追加的 AVG/MIN/MAX/样本数/最差质量/最晚接收时间事实。

管理服务 V30 开放策略类型，V31 创建重算请求和治理权限，V32 增加 `FOR UPDATE SKIP LOCKED` 所需的可恢复租约字段与索引；Telemetry V17～V20 依次创建策略镜像、版本化事实、源查询索引和显式重算身份。所有新增表和字段均使用中文注释，PostgreSQL/TimescaleDB 集成测试校验空库与升级迁移、注释、约束、索引、不可变触发器、旧 token 拒绝和稳定执行重放。

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
- 原始数值遥测和质量证据按最后正确 `RETENTION` 策略有界清理；首版不删除 Inbox、最新值、告警状态/Outbox、策略镜像/水位、服务端告警和审计。
- 审计按长期策略归档，普通管理员无删除权限。

## 16. Migration 规则

- 文件命名：V{版本}__{中文或英文说明}.sql。
- migration 一旦进入正式版本不可修改校验和。
- 大表变更使用 expand/contract。
- 新列先 nullable/有默认兼容，再发布应用，再收紧约束。
- 索引创建评估锁表和磁盘。
- Timescale extension 升级单独编写 preflight 和恢复说明。
