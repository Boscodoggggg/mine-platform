# Device Access Status Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 增加设备四层接入状态、持续在线判定和可手动点击执行的完整接入检查。

**Architecture:** Telemetry 在持久事务中记录首帧与最后接收时间；mine-server 通过可选只读遥测连接计算当前页设备状态，并在手动检查时额外查询 RabbitMQ 活动连接和复用现有安全端点探针。前端在设备台账与接入抽屉展示稳定原因码国际化后的结果。

**Tech Stack:** Java 21、Spring Boot、MyBatis、PostgreSQL/TimescaleDB、Redis、RabbitMQ HTTP API、Vue 3、Naive UI、Vue I18n、Vitest、JUnit 5、Testcontainers。

---

### Task 1: 扩展遥测持久接入证据

**Files:**
- Create: `mine-telemetry/src/main/resources/db/migration/V9__add_device_activity_evidence.sql`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/storage/DurableLatestWriter.java`
- Test: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryStorageIT.java`
- Test: `mine-telemetry/src/test/java/com/bosco/mine/telemetry/storage/TelemetryMigrationIT.java`

1. 先写失败测试，要求第一条有效报文记录 `first_seen_at`，后续报文只推进 `last_received_at`，重复或迟到报文不得回退时间。
2. 运行目标测试并确认因字段不存在失败。
3. 新增带完整中文注释的 V9 migration，并在现有遥测事务中维护两个时间字段。
4. 运行存储和迁移测试至通过。

### Task 2: 定义接入状态契约与查询端口

**Files:**
- Modify: `docs/openapi/mine-api.yaml`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/dto/DeviceAccessStatusResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/dto/DeviceAccessTestResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/repository/DeviceActivityRepository.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/repository/TimescaleDeviceActivityRepository.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/device/repository/TimescaleDeviceActivityRepositoryTest.java`

1. 先写失败测试，定义批量读取设备首帧和最后接收时间的端口以及依赖不可用语义。
2. 增加 OpenAPI 状态、阶段和手动检查接口契约。
3. 实现只读 TimescaleDB 查询；未启用查询连接时由服务返回未知。
4. 重新生成前端类型并运行契约测试。

### Task 3: 实现 Broker 会话检查与四层聚合

**Files:**
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/infrastructure/RabbitUserAdminClient.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/repository/DeviceMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceAccessStatusService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/controller/DeviceAccessStatusController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/device/service/DeviceAccessStatusServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/device/controller/DeviceAccessStatusControllerTest.java`

1. 先写失败测试覆盖未配置、等待首帧、仅传输连接、在线、陈旧和依赖未知。
2. 扩展 RabbitMQ 客户端按活动凭据查询连接，只返回脱敏会话证据。
3. 实现状态计算与 `POST /devices/{id}/access-status/test`，复用现有端点探针并记录审计。
4. 运行服务和控制器测试至通过。

### Task 4: 设备台账持续状态与手动按钮

**Files:**
- Modify: `mine-web/src/modules/device/api/device.ts`
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.vue`
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/typings/app.d.ts`

1. 先写失败页面测试，要求设备台账显示状态与最后上报，点击“检查接入状态”调用新接口并展示四层结果。
2. 实现批量状态加载、语义标签、时间展示和手动检查按钮。
3. 所有用户文案进入中英文 locale，业务页面不新增样式或硬编码颜色。
4. 运行页面测试、typecheck 和 UI 治理测试。

### Task 5: 文档、回归与提交

**Files:**
- Modify: `docs/runbooks/device-onboarding.md`
- Modify: `docs/05-messaging-telemetry-data.md`

1. 更新四层状态口径、手动检查步骤和依赖不可用处理。
2. 运行 `./mvnw -B -ntp test`、`pnpm --dir mine-web lint:check`、`pnpm --dir mine-web test --maxWorkers=1`、`pnpm --dir mine-web typecheck`、`pnpm --dir mine-web build`。
3. 运行 `git diff --check`，只提交本功能文件，保留用户现有工作区变更。
4. 使用 `feat(device): 新增设备四层接入状态检查` 提交。
