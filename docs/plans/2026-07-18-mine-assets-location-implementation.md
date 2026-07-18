# 矿区资产、时间绑定与模拟定位纵向闭环 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付“集团/矿区 → 车辆/人员/标签 → 时间绑定 → 模拟定位 → 双坐标位置事实 → 实时地图与轨迹”的可部署纵向闭环，同时保留真实厂商、现场标定、容量、HA 和长稳验证阻断项。

**Architecture:** 管理主数据、数据作用域、资质和绑定保存在 `mine-server` 的 PostgreSQL；Gateway 通过独立定位主题和队列校验可信设备身份并发布稳定 `LocationObservation`；Telemetry 使用最后正确运行快照按发生时间解析绑定、执行版本化坐标转换，把双坐标事实写入现有 `location_point` hypertable，再以顺序保护更新 Redis 当前位置并发布实时事件；Server 复用遥测只读连接和统一 WebSocket 向前端提供有界轨迹、快照和增量。动作权限继续使用 RBAC，组织/矿区数据范围由服务端独立强制校验。

**Tech Stack:** Java 21、Spring Boot 4.1、Sa-Token、MyBatis、PostgreSQL 18、TimescaleDB OSS、Redis、RabbitMQ 4、Vue 3、TypeScript、Naive UI、UnoCSS、ECharts 6、Vitest、Playwright、Testcontainers。

---

## 实施锁定项

- 一级菜单固定为“矿区管理”，首批页面为矿区档案、车辆管理、人员管理、定位标签、绑定管理和实时定位；设备产品、物模型、凭据和连接仍属于“设备管理”。
- 数据范围固定为“组织/客户集团 → 矿区”两级；当前产品按单客户独立部署，不实现注册、计费、套餐或跨客户运维控制面。
- Bootstrap `SUPER_ADMIN` 绕过数据范围检查；其他用户即使拥有 RBAC 动作权限，也必须拥有 `mine_user_scope_grant`。客户端传入的 `organizationId`、`siteId` 只用于定位资源，不能成为授权事实。
- 定位上行使用独立 MQTT 主题 `mine.v1.{productKey}.{clientId}.up.location` 和独立原始队列；内部定位观测使用 `mine.telemetry.x` 的稳定路由键 `location.observation` 和独立 quorum 队列，避免与数值遥测相互阻塞。
- 不给 `UnifiedDeviceMessage.properties` 塞坐标；新增强类型 `LocationObservation`，数值遥测契约保持兼容。
- 现有 TimescaleDB `location_point` 原位升级，不创建语义重复的第二张轨迹表。每个事实同时保存原始坐标、统一矿区坐标、转换版本、发生时绑定和固定质量证据。
- 定位实时主题固定为 `MINE_SITE_LOCATION`，只允许订阅已授权矿区；实时事件不包含手机号、证件号或其他人员敏感字段。
- 前端二维画布使用 ECharts 本地坐标系，不依赖公网地图瓦片。所有页面遵守 Naive UI、UnoCSS、统一主题 token 和中英文国际化规则。
- 每个任务先写失败测试，再做最小实现，再运行定向门禁并使用中文 Conventional Commit；后端新增 Java 类型和公共方法必须写中文 Javadoc。

## Task 1：建立组织、矿区和数据授权数据库不变量

**Files:**

- Create: `mine-server/src/main/resources/db/migration/V33__create_mine_scope_foundation.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Step 1: 写失败的迁移测试**

在 `EXPECTED_TABLES` 增加 `mine_organization`、`mine_site`、`mine_user_scope_grant`，并新增测试证明：

- 三张表及每个字段都有中文注释；
- 组织编码全局唯一，矿区编码只在组织内唯一；
- `mine_site(id, organization_id)` 可作为复合外键目标；
- 授权的 `site_id` 为空表示组织级授权，`UNIQUE NULLS NOT DISTINCT` 禁止重复授权；
- 授权矿区必须属于同一个 `organization_id`；
- 状态、时区、归档时间和更新时间约束不能被非法数据绕过。

Run: `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT test`

Expected: FAIL，提示三张表或约束不存在。

**Step 2: 编写 V33 迁移**

迁移至少创建以下稳定结构：

```sql
CREATE TABLE mine_organization (... organization_code VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, ...);
CREATE TABLE mine_site (... organization_id UUID NOT NULL, site_code VARCHAR(64) NOT NULL, timezone VARCHAR(64) NOT NULL, ...);
CREATE TABLE mine_user_scope_grant (... user_id UUID NOT NULL, organization_id UUID NOT NULL, site_id UUID, ...);
ALTER TABLE mine_site ADD CONSTRAINT uk_mine_site_id_organization UNIQUE (id, organization_id);
ALTER TABLE mine_user_scope_grant ADD CONSTRAINT fk_mine_scope_site_organization
  FOREIGN KEY (site_id, organization_id) REFERENCES mine_site(id, organization_id);
```

为表和每个字段逐一添加 `COMMENT ON TABLE` / `COMMENT ON COLUMN`，为业务编码使用大小写归一后的唯一索引，为外键、状态和常用列表建立有界索引。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT test`

Expected: PASS。

Commit: `feat(mine): 建立组织矿区与数据授权表`

## Task 2：实现矿区上下文、数据授权和档案 API

**Files:**

- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/model/MineOrganization.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/model/MineSite.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/repository/MineScopeMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/service/MineScopeAuthorizer.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/service/MineScopeQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/service/MineScopeCommandService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/controller/MineScopeController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/MineContextResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/MineOrganizationRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/MineOrganizationResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/MineSiteRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/MineSiteResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/MineScopeGrantRequest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/service/MineScopeAuthorizerTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/MineScopeManagementIT.java`

**Step 1: 写服务和 PostgreSQL 集成失败测试**

覆盖 Bootstrap 管理员全局访问、组织级授权覆盖所属矿区、矿区级授权只允许单矿、跨组织伪造 `siteId` 拒绝、禁用用户/归档矿区拒绝、创建组织时给创建者组织级授权、授权增删幂等、列表只返回可见范围、所有成功写操作产生脱敏审计。

Run: `./mvnw -pl mine-server -Dtest=MineScopeAuthorizerTest,MineScopeManagementIT test`

Expected: FAIL，类型和 Bean 尚不存在。

**Step 2: 实现唯一授权入口**

`MineScopeAuthorizer` 暴露 `requireOrganization(UUID)`、`requireSite(UUID)` 和批量可见 ID 查询；Bootstrap 管理员以 `MineSecurityProperties.bootstrapAdmin().id()` 判定，不把 `SUPER_ADMIN` 文本或客户端角色声明作为数据依据。业务服务不得自行拼一套作用域 SQL。

**Step 3: 实现 API 和审计**

提供：

- `GET /api/v1/mine/context`
- `GET/POST/PATCH /api/v1/mine/organizations`
- `GET/POST/PATCH /api/v1/mine/sites`
- `GET/PUT/DELETE /api/v1/mine/scope-grants`

控制器使用 `StpUtil.checkPermission` 强制 `mine:organization:*`、`mine:site:*`、`mine:scope:assign`；写服务使用 `@Transactional`、UUIDv7、稳定错误码和 `OperationAuditService`。

**Step 4: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=MineScopeAuthorizerTest,MineScopeManagementIT test`

Expected: PASS。

Commit: `feat(mine): 提供矿区作用域与档案接口`

## Task 3：建立车辆、标签和版本化坐标模型

**Files:**

- Create: `mine-server/src/main/resources/db/migration/V34__create_mine_assets_and_coordinates.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/MineAssetConstraintIT.java`

**Step 1: 写失败的数据库约束测试**

覆盖 `mine_vehicle`、`mine_location_tag`、`mine_coordinate_system`、`mine_coordinate_transform_version` 的表/字段中文注释，矿区内编码唯一、跨矿可重用；统一坐标系每矿最多一个；源/目标坐标系必须属于同一矿区；发布版本参数、摘要、有效期不可原地修改；本地坐标边界必须满足 `min < max`。

Run: `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT,MineAssetConstraintIT test`

Expected: FAIL。

**Step 2: 创建资产和坐标迁移**

坐标类型固定为 `LOCAL_CARTESIAN`、`WGS84`、`ENU`，转换算法固定为 `IDENTITY`、`AFFINE_3D`、`WGS84_ENU`。转换参数使用 JSONB，但数据库约束和发布服务都必须校验算法所需字段；发布后的版本由触发器禁止 UPDATE/DELETE，只能创建更高版本。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT,MineAssetConstraintIT test`

Expected: PASS。

Commit: `feat(mine): 建立车辆标签与坐标版本模型`

## Task 4：实现车辆、标签和坐标管理 API

**Files:**

- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/repository/MineAssetMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/service/MineAssetQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/service/MineAssetCommandService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/controller/MineAssetController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/VehicleRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/VehicleResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/LocationTagRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/LocationTagResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/CoordinateSystemRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/CoordinateTransformRequest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/MineAssetManagementIT.java`

**Step 1: 写失败的管理集成测试**

覆盖作用域隔离、分页上限、乐观版本冲突、跨矿坐标引用拒绝、发布转换后不可修改、已被引用资产只能归档、审计和固定错误码。

**Step 2: 实现 CRUD 与发布动作**

API 使用 `/api/v1/mine/sites/{siteId}/vehicles`、`/location-tags`、`/coordinate-systems` 和 `/coordinate-transforms`；所有 Mapper 查询同时带授权后的组织/矿区条件，更新使用 `WHERE id = ? AND revision = ?`。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=MineAssetManagementIT test`

Expected: PASS。

Commit: `feat(mine): 提供车辆标签与坐标管理接口`

## Task 5：实现人员档案、司机资质和敏感字段保护

**Files:**

- Create: `mine-server/src/main/resources/db/migration/V35__create_mine_people_and_qualifications.sql`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/security/SensitiveFieldCipher.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/security/AesGcmSensitiveFieldCipher.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/security/MineSensitiveDataProperties.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/repository/MinePersonMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/service/MinePersonService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/controller/MinePersonController.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/security/AesGcmSensitiveFieldCipherTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/MinePersonManagementIT.java`
- Modify: `mine-server/src/main/resources/application.yml`
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/secrets.example/README.md`

**Step 1: 写失败测试**

证明人员编码按组织唯一、任职区间合法、资质允许车型和有效期受约束；AES-256-GCM 每次随机 nonce、错误密钥和篡改密文拒绝；普通查看只返回掩码，`mine:person:sensitive:view` 才能解密且必须新增读取审计；日志和审计不能出现明文手机号或证件号。

**Step 2: 实现密钥文件和敏感 API**

密钥只从 `/run/secrets/mine_person_data_key` 读取，密文保存版本前缀、nonce 和认证标签。列表/详情默认返回 `phoneMasked`、`certificateMasked`；显式敏感读取使用独立接口和权限，不能通过通用 DTO 序列化泄露。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=AesGcmSensitiveFieldCipherTest,MinePersonManagementIT test`

Expected: PASS。

Commit: `feat(mine): 管理人员资质并保护敏感字段`

## Task 6：建立数据库级时间绑定排斥约束

**Files:**

- Create: `mine-server/src/main/resources/db/migration/V36__create_mine_temporal_bindings.sql`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/MineBindingConstraintIT.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Step 1: 写失败的并发和约束测试**

用两个真实 PostgreSQL 事务证明：同一设备不能重叠绑定两辆车；同一车辆不能重叠存在两个 `PRIMARY` 设备；车辆标签和人员标签跨表也不能重叠；司机不能重叠驾驶两车且车辆不能同时有两个主司机；半开区间首尾相接合法。

**Step 2: 实现四类绑定和共享标签占用台账**

迁移启用 `btree_gist`，创建：

- `mine_vehicle_device_binding`
- `mine_location_tag_binding_claim`
- `mine_vehicle_tag_binding`
- `mine_person_tag_binding`
- `mine_person_vehicle_binding`

每张业务绑定表保留真实外键；`mine_location_tag_binding_claim` 只承担跨车辆/人员标签表的数据库排斥账本，不作为通用业务多态关系。使用延迟约束触发器保证 claim 与恰好一个类型表同事务落库，所有区间使用生成列 `tstzrange(valid_from, valid_to, '[)')` 和 GiST `EXCLUDE`。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT,MineBindingConstraintIT test`

Expected: PASS。

Commit: `feat(mine): 建立矿区资产时间绑定约束`

## Task 7：实现预览、换绑和发生时解析服务

**Files:**

- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/repository/MineBindingMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/service/MineBindingService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/controller/MineBindingController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/BindingPreviewResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/BindingSwitchRequest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/MineBindingServiceIT.java`

**Step 1: 写失败测试**

覆盖跨矿拒绝、归档对象拒绝、司机资质车型/有效期拒绝、当前/未来换绑关闭旧区间并新建区间、过去历史修改拒绝、并发冲突返回稳定业务错误、审计和 Outbox 事件同事务。

**Step 2: 实现无通用 CRUD 的换绑 API**

提供 `/preview` 和 `/switch`，要求 `effectiveAt`、`reason` 和 `Idempotency-Key`。预览返回旧关系、目标关系、资质判断和受影响对象；执行再次校验全部条件，不能信任旧预览结果。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=MineBindingServiceIT test`

Expected: PASS。

Commit: `feat(mine): 提供可审计的资产换绑流程`

## Task 8：建立定位运行快照控制面

**Files:**

- Create: `mine-server/src/main/resources/db/migration/V37__create_location_runtime_snapshots.sql`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/location/LocationSnapshotService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/location/LocationSnapshotController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/location/LocationSnapshotOutboxEvent.java`
- Create: `mine-messaging/src/main/java/com/bosco/mine/messaging/location/LocationRuntimeChangedPayload.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/command/outbox/RabbitLocationSnapshotOutboxPublisher.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/LocationSnapshotPublishingIT.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/command/outbox/RoutingOutboxEventPublisher.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/command/outbox/CommandOutboxConfiguration.java`
- Modify: `mine-messaging/src/main/java/com/bosco/mine/messaging/topology/MineTopology.java`

**Step 1: 写失败测试**

覆盖同一规范内容幂等、修订单调递增、分页完整、SHA-256 摘要稳定、发布后不可改、资产或绑定事务成功才产生刷新请求、服务令牌才能拉取内部快照。

**Step 2: 实现完整不可变快照**

快照内容只包含定位热路径所需的非敏感字段：矿区边界、标签、可信来源设备、坐标系、已发布转换版本和绑定区间。内部接口为 `GET /api/v1/internal/location-snapshots/latest` 与分页详情；Outbox 只发布 `snapshotId/revision/contentHash`，不把完整快照塞进 RabbitMQ。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -am -Dtest=LocationSnapshotPublishingIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

Commit: `feat(mine): 发布定位运行快照`

## Task 9：新增定位消息契约与最小权限 RabbitMQ 拓扑

**Files:**

- Create: `mine-device-model/src/main/java/com/bosco/mine/model/location/LocationObservation.java`
- Create: `mine-device-model/src/test/java/com/bosco/mine/model/location/LocationObservationTest.java`
- Create: `mine-messaging/src/main/java/com/bosco/mine/messaging/realtime/RealtimeLocationEvent.java`
- Modify: `mine-messaging/src/main/java/com/bosco/mine/messaging/topology/MineTopology.java`
- Modify: `mine-messaging/src/test/java/com/bosco/mine/messaging/RabbitTopologyIT.java`
- Modify: `deploy/config/rabbitmq/definitions.json`
- Modify: `deploy/scripts/rabbitmq-bootstrap.sh`
- Modify: `deploy/tests/compose-config.sh`
- Modify: `deploy/tests/standalone-smoke.sh`

**Step 1: 写失败的契约和拓扑测试**

`LocationObservation` 校验 UUIDv7、可信设备、标签编码、坐标系编码、有限坐标、速度、航向、精度、启动序号和时间；拓扑测试要求独立原始定位队列、独立平台 location quorum 队列、DLX、精确绑定和最小账号权限。

**Step 2: 实现稳定契约与拓扑**

新增常量：

```java
DEVICE_LOCATION_UPLINK_QUEUE
DEVICE_LOCATION_ROUTING_PATTERN
LOCATION_QUEUE
LOCATION_OBSERVATION_ROUTING_KEY
LOCATION_CONFIG_EXCHANGE
TELEMETRY_LOCATION_CONFIG_QUEUE
REALTIME_LOCATION_ROUTING_PATTERN
```

扩展设备 topic write 权限只允许本设备 telemetry、location、command-ack；Telemetry 账号只新增读取 location/config 队列，Gateway 平台账号仍只写受控交换机。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-device-model,mine-messaging -am test`

Run: `bash deploy/tests/compose-config.sh`

Expected: PASS。

Commit: `feat(location): 定义定位消息与可靠拓扑`

## Task 10：让 Gateway 可信接入模拟定位观测

**Files:**

- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/location/LocationMessageNormalizer.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/location/LocationMessageConsumer.java`
- Create: `mine-gateway/src/main/java/com/bosco/mine/gateway/location/ConfirmedLocationPublisher.java`
- Create: `mine-gateway/src/test/java/com/bosco/mine/gateway/location/LocationMessageNormalizerTest.java`
- Create: `mine-gateway/src/test/java/com/bosco/mine/gateway/location/LocationMessageConsumerTest.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/bootstrap/GatewayRabbitConfiguration.java`
- Modify: `mine-gateway/src/test/java/com/bosco/mine/gateway/mqtt/DeviceMessageConsumerIT.java`

**Step 1: 写失败测试**

覆盖 topic 中 product/client 与凭据快照一致、payload 中 product/device 不能冒充、有限坐标和大小上限、Publisher confirm 后才 ACK、永久输入错误 reject、瞬时发布失败 requeue；真实 RabbitMQ IT 证明 `/mine-device` 到 `/mine-platform` 纵向转换。

**Step 2: 实现独立定位消费者**

固定 JSON v1 字段，不走动态物模型属性映射。Gateway 只做身份和边界验证，不决定矿区、绑定对象或统一坐标；这些由 Telemetry 的已激活快照解析。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-gateway -am test`

Run: `./mvnw -pl mine-gateway -am verify -DskipUnitTests`

Expected: PASS。

Commit: `feat(gateway): 可信接入定位观测`

## Task 11：升级 TimescaleDB 双坐标位置事实和隔离证据

**Files:**

- Create: `mine-telemetry/src/main/resources/db/migration/V21__upgrade_location_fact_model.sql`
- Modify: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryMigrationIT.java`

**Step 1: 写失败的迁移测试**

证明 `location_point` 具备 organization/site、来源设备、标签、原始坐标系和原始 xyz、目标坐标系和统一 xyz、transform version、binding type/id、vehicle/person、accuracy、quality status/reason、ingest sequence 字段；新增 current rebuild watermark、snapshot stage/revision、location inbox、bounded quarantine 和 location realtime outbox 表；全部表和字段有中文注释。

**Step 2: 用 V21 原位升级**

保留现有 `x/y/z` 作为统一坐标并重命名为 `local_x/local_y/local_z`，新增 `source_x/source_y/source_z`，禁止先复制再删历史表。唯一幂等约束同时覆盖事件和 hypertable 时间分区要求，常用索引按 site/tag/vehicle/person + time 建立。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-telemetry -Dtest=TelemetryMigrationIT test`

Expected: PASS。

Commit: `feat(location): 升级双坐标位置事实模型`

## Task 12：实现可验证的坐标转换内核

**Files:**

- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/transform/CoordinateTransformer.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/transform/IdentityTransformer.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/transform/Affine3dTransformer.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/transform/Wgs84EnuTransformer.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/transform/CoordinateTransformRegistry.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/location/transform/CoordinateTransformRegistryTest.java`

**Step 1: 写失败的纯单元测试**

使用公开数学基准证明恒等、4x4 仿射矩阵、WGS84 原点/东/北/高方向；覆盖非有限参数、奇异矩阵、纬度经度越界、错误算法参数和结果越矿区边界。

**Step 2: 实现无 IO 的不可变转换器**

转换器输入不可变快照和源坐标，输出统一米制坐标与 transformVersionId；禁止隐式读取数据库或系统时钟，方便重放得到同一结果。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-telemetry -Dtest=CoordinateTransformRegistryTest test`

Expected: PASS。

Commit: `feat(location): 实现版本化双坐标转换`

## Task 13：实现 Telemetry 最后正确定位快照镜像

**Files:**

- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/snapshot/LocationSnapshotLoader.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/snapshot/HttpLocationSnapshotSource.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/snapshot/LocationRuntimeRegistry.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/snapshot/LocationSnapshotConsumer.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/location/snapshot/LocationSnapshotLoaderIT.java`
- Modify: `mine-telemetry/src/main/resources/application.yml`

**Step 1: 写失败测试**

覆盖分页缺失、摘要错误、低修订、同修订异内容、非法坐标参数拒绝且不覆盖 last-known-good；完整更高修订暂存、校验、原子激活；重启从数据库恢复已激活快照；Server 暂时不可用时继续使用最后正确版本。

**Step 2: 复用现有策略快照可靠模式**

只复用阶段/错误语义，不复用策略领域类型。加载阶段固定为 `FETCH/STAGE/VERIFY/ACTIVATE/RESTORE`，指标 label 只允许固定结果和阶段。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-telemetry -Dtest=LocationSnapshotLoaderIT test`

Expected: PASS。

Commit: `feat(location): 激活最后正确定位快照`

## Task 14：实现定位摄取、质量、历史、缓存和实时发布

**Files:**

- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/LocationObservationConsumer.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/LocationFactWriter.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/LocationQualityEvaluator.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/LocationLatestCache.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/ConfirmedRealtimeLocationPublisher.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/location/LocationRealtimeOutboxRelay.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/location/LocationObservationConsumerTest.java`
- Create: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/location/LocationPipelineIT.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/bootstrap/TelemetryStorageConfiguration.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/bootstrap/TelemetryRabbitProperties.java`

**Step 1: 写失败的纵向 Testcontainers 测试**

证明按 `occurredAt` 解析绑定并固化 bindingId；换绑前后轨迹各归原对象；重复不重复写；乱序保留历史但不倒退 Redis；低质量保存 `DEGRADED` 且不覆盖更好当前位置；未绑定标签只保存 tag 级历史；未知/归档标签、越权设备、非有限/越界坐标、转换缺失进入有界隔离并 reject；Redis 故障不回滚 TimescaleDB，恢复可重建当前位置。

**Step 2: 实现单事务历史事实和可靠提交后副作用**

处理顺序固定为：inbox 幂等 → 快照解析 → 坐标转换 → 质量判定 → 位置事实与实时 Outbox 同事务提交 → ACK 原始观测 → 顺序保护 Redis → Outbox publisher-confirm 实时事件。Redis 暂时失败不能回滚历史，恢复任务从最新事实回填；实时发布失败由带租约、退避和尝试上限的 Outbox relay 精确重试，不能让原始定位消息无限重投。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-telemetry -Dtest=LocationObservationConsumerTest test`

Run: `./mvnw -pl mine-telemetry -Dit.test=LocationPipelineIT verify -DskipUnitTests`

Expected: PASS。

Commit: `feat(location): 打通模拟定位存储与实时链路`

## Task 15：提供有界位置查询和矿区实时 WebSocket

**Files:**

- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/location/LocationQueryRepository.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/location/LocationQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/location/LocationController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/CurrentLocationResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/dto/LocationTrajectoryResponse.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/LocationQueryIT.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeRabbitConfiguration.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeLocationConsumer.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeSessionRegistry.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/websocket/RealtimeWebSocketHandler.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/bootstrap/websocket/RealtimeWebSocketHandlerTest.java`

**Step 1: 写失败测试**

覆盖 Redis 当前值读取和 Timescale 降级回填；矿区作用域过滤；轨迹最大 24 小时、最大 5,000 输出点、固定 cursor/watermark 和服务端 LTTB 抽稀；WebSocket 鉴权后只订阅授权 site，权限或授权撤销后下一次心跳/动作失效，跨矿实时事件不泄露。

**Step 2: 实现 API 和统一实时主题**

提供：

- `GET /api/v1/mine/sites/{siteId}/locations/current`
- `GET /api/v1/mine/sites/{siteId}/locations/trajectory`
- WebSocket `SUBSCRIBE` topic `{ "type": "MINE_SITE_LOCATION", "ids": [siteId] }`

实时 Rabbit 临时队列同时绑定 `realtime.telemetry.#` 和 `realtime.location.#`，消费者按事件类型解码；连接内继续使用统一 sequence、断档 `RESYNC` 和快照机制。

**Step 3: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=LocationQueryIT,RealtimeWebSocketHandlerTest test`

Expected: PASS。

Commit: `feat(mine): 提供位置查询与矿区实时订阅`

## Task 16：发布 OpenAPI、菜单权限和前端矿区上下文

**Files:**

- Create: `mine-server/src/main/resources/db/migration/V38__publish_mine_management_navigation.sql`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/system/service/MenuManagementService.java`
- Modify: `docs/openapi/mine-api.yaml`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.test.ts`
- Create: `mine-web/src/modules/mine/api/mine-api.ts`
- Create: `mine-web/src/modules/mine/stores/mine-context.ts`
- Create: `mine-web/src/modules/mine/stores/mine-context.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Step 1: 写失败的后端菜单和前端导航测试**

固定 UUIDv7 菜单 ID：`...0600` 为矿区管理，`...0610` 至 `...0660` 为六个页面；覆盖目录排序、componentKey 白名单、i18n key、查看/编辑/敏感/绑定/定位按钮权限和未知组件不出现。

**Step 2: 发布契约并生成客户端**

在 OpenAPI 增加矿区、资产、人员、绑定、位置和错误契约，所有分页/时间窗/枚举/幂等头有边界。Run: `pnpm --dir mine-web gen:api`，禁止手改生成文件。

**Step 3: 实现矿区上下文**

Pinia 只保存当前会话选中的 `siteId`，候选列表每次由 `/mine/context` 得到；授权被撤销时自动清理选择并提示，不能用 localStorage 中的旧值绕过服务端。

**Step 4: 验证并提交**

Run: `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT test`

Run: `pnpm --dir mine-web test:unit -- src/app/router/dynamic-routes.test.ts src/modules/mine/stores/mine-context.test.ts`

Expected: PASS。

Commit: `feat(frontend): 发布矿区管理菜单与上下文`

## Task 17：实现矿区、车辆、人员、标签和绑定页面

**Files:**

- Create: `mine-web/src/modules/mine/pages/site-archive/MineSiteArchivePage.vue`
- Create: `mine-web/src/modules/mine/pages/vehicle-management/VehicleManagementPage.vue`
- Create: `mine-web/src/modules/mine/pages/person-management/PersonManagementPage.vue`
- Create: `mine-web/src/modules/mine/pages/tag-management/LocationTagManagementPage.vue`
- Create: `mine-web/src/modules/mine/pages/binding-management/BindingManagementPage.vue`
- Create: matching `*.test.ts` files beside every page
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Step 1: 写失败的页面测试**

覆盖加载/空/错误/分页、作用域切换、权限按钮、乐观冲突、归档确认、默认脱敏、敏感字段独立授权、资质过期提示、换绑预览二次确认、键盘操作和移动窄屏堆叠。

**Step 2: 用 Naive UI 和 UnoCSS 实现页面**

列表沿用项目统一页面结构；换绑使用步骤式 Drawer 展示旧关系、目标对象、切换时刻、资质和影响，不提供直接编辑历史区间。禁止 `<style>`、内联样式、硬编码颜色和用户可见硬编码文本。

**Step 3: 验证并提交**

Run: `pnpm --dir mine-web test:unit -- src/modules/mine`

Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check`

Expected: PASS。

Commit: `feat(frontend): 交付矿区资产与绑定管理页面`

## Task 18：实现实时定位画布、轨迹和弱网恢复

**Files:**

- Create: `mine-web/src/modules/mine/pages/realtime-location/RealtimeLocationPage.vue`
- Create: `mine-web/src/modules/mine/pages/realtime-location/RealtimeLocationPage.test.ts`
- Create: `mine-web/src/modules/mine/components/MineCoordinateCanvas.vue`
- Create: `mine-web/src/modules/mine/components/MineCoordinateCanvas.test.ts`
- Create: `mine-web/src/modules/mine/composables/useMineLocationRealtime.ts`
- Create: `mine-web/src/modules/mine/composables/useMineLocationRealtime.test.ts`
- Modify: `mine-web/src/realtime/RealtimeHub.ts`
- Modify: `mine-web/src/realtime/RealtimeHub.test.ts`
- Create: `mine-web/e2e/mine-location.spec.ts`

**Step 1: 写失败的交互和实时测试**

覆盖先拉当前位置再建 WebSocket、批量增量、sequence 断档 RESYNC、弱网重连、矿区切换清订阅、质量/新鲜度显示、车辆/人员筛选、详情侧栏、24 小时内轨迹、服务端抽稀提示、空状态和 ECharts resize/dispose。

**Step 2: 实现离线二维矿区画布**

使用坐标轴、scatter/custom series 和 line series；颜色只能从 Naive UI theme token 计算为 ECharts option，页面不硬编码色值。标签默认展示业务编码/掩码名称，模拟数据有持续可见的“模拟”标识。

**Step 3: 验证并提交**

Run: `pnpm --dir mine-web test:unit -- src/realtime src/modules/mine`

Run: `pnpm --dir mine-web exec playwright test e2e/mine-location.spec.ts`

Expected: PASS。

Commit: `feat(frontend): 交付矿区实时定位与轨迹`

## Task 19：增加定位模拟场景、可观测性和自动化验收

**Files:**

- Create: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/location/LocationRouteGenerator.java`
- Create: `mine-device-simulator-app/src/test/java/com/bosco/mine/simulator/location/LocationRouteGeneratorTest.java`
- Modify: `mine-device-simulator-app/src/main/java/com/bosco/mine/simulator/payload/PayloadRenderer.java`
- Modify: `mine-device-simulator-app/src/main/resources/static/index.html`
- Modify: `mine-device-simulator-app/src/main/resources/static/app.js`
- Modify: `deploy/observability/prometheus/rules/mine-platform.yml`
- Modify: `deploy/tests/prometheus-rules.sh`
- Create: `docs/runbooks/mine-location-runtime.md`
- Create: `docs/reports/phase-3-mine-location-acceptance.md`
- Modify: `docs/06-mine-business.md`
- Modify: `docs/11-contracts-and-database.md`
- Modify: `docs/12-roadmap.md`

**Step 1: 写失败的模拟轨迹测试**

固定种子下生成可重复路线，支持正常圆形/折线、低质量、重复、乱序、未绑定标签和越界场景；序号、发生时间、速度、航向和坐标必须自洽，页面清楚标识模拟数据。

**Step 2: 加入低基数指标和告警**

指标至少包含 location ingest result/reason、snapshot stage/result、transform algorithm/result、Redis latest result 和 quarantine size；禁止 site、device、tag、person、vehicle、坐标、异常文本进入 label。告警覆盖快照长期失败、定位积压、拒绝率突增、当前位置缓存持续失败和隔离区逼近上限。

**Step 3: 执行完整自动化门禁**

Run: `./mvnw test`

Run: `./mvnw verify -DskipUnitTests`

Run: `pnpm --dir mine-web check:ui-governance`

Run: `pnpm --dir mine-web typecheck`

Run: `pnpm --dir mine-web lint:check`

Run: `pnpm --dir mine-web test:unit`

Run: `pnpm --dir mine-web build`

Run: `bash deploy/tests/compose-config.sh && bash deploy/tests/prometheus-rules.sh`

Expected: 全部 PASS。

**Step 4: 记录自动化结论和现场阻断项**

验收报告必须分别列出仓库自动化证据与未执行现场项。真实 UWB 厂商协议、矿区坐标标定、真实误差、目标设备频率/容量、HA、备份恢复和 24/72 小时长稳仍标记为 `BLOCKED/DEFERRED`，不得据此宣称已完成生产发布资格。

Commit: `docs(mine): 完成模拟定位自动化验收`

## 批次执行建议

1. **批次 A（作用域与主数据）**：Task 1–5。完成后可独立销售/演示多矿区资产台账与人员资质。
2. **批次 B（绑定与运行配置）**：Task 6–9。完成后形成可追溯时间关系和定位热路径配置面。
3. **批次 C（定位纵向链路）**：Task 10–15。完成后模拟点能够可靠进入双坐标历史、当前位置和实时订阅。
4. **批次 D（产品界面与验收）**：Task 16–19。完成后六个“矿区管理”菜单形成可演示、可操作、可观测闭环。

每个批次结束都更新 `docs/reports/phase-3-mine-location-acceptance.md`，但只在 Task 19 全部自动化门禁通过后给出仓库层面的完成结论。
