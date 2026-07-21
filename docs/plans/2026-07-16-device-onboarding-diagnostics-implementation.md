# 设备接入与连通性诊断 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为设备台账增加可版本化的接入配置，并允许接入工程师从管理端执行配置、DNS、TCP 和 TLS 分层诊断。

**Architecture:** 每台设备保存一份不含明文密钥的连接配置，区分设备主动接入平台和平台主动连接设备。后端通过可替换的网络探针执行有超时的分层检查并保存最近一次结果；前端在设备列表中提供接入配置抽屉和诊断时间线。设备主动接入场景只证明平台端点可用，最终上线仍明确要求设备首帧确认。

**Tech Stack:** PostgreSQL/Flyway、Spring Boot、MyBatis、Java Socket/SSL、OpenAPI、Vue 3、Naive UI、Vue I18n、Vitest。

---

### Task 1: 固化数据库和 OpenAPI 契约

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V15__create_device_connection_profile.sql`
- Modify: `docs/openapi/mine-api.yaml`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/device/DeviceConnectionProfileRepositoryIT.java`

**Steps:**
1. 先写 PostgreSQL 集成测试，要求连接配置一设备唯一、枚举和端口受约束、表及全部字段都有中文注释。
2. 运行目标测试，确认因表不存在而失败。
3. 新增 migration，保存接入方式、主机、端口、TLS、超时、最近诊断摘要和乐观锁版本，不保存任何密码。
4. 补充 OpenAPI 的查询、保存和测试端点及 schema。
5. 重新运行目标测试并确认通过。

### Task 2: 实现后端接入配置与分层探针

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/model/DeviceConnectionProfile.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/dto/DeviceConnectionProfileResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/dto/DeviceConnectionProfileRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/dto/DeviceConnectionTestResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/repository/DeviceConnectionProfileMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/infrastructure/DeviceConnectionProbe.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/infrastructure/SocketDeviceConnectionProbe.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/service/DeviceConnectionService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/device/controller/DeviceConnectionController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/device/service/DeviceConnectionServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/device/controller/DeviceConnectionControllerTest.java`

**Steps:**
1. 先写服务测试，覆盖保存配置、设备不存在、分层结果保存和设备主动接入提示。
2. 运行测试并确认类或行为缺失导致失败。
3. 实现最小 service、mapper、DTO 和审计记录。
4. 先写探针测试，使用本机临时 ServerSocket 验证 DNS/TCP 成功和关闭端口失败。
5. 实现带超时的 DNS、TCP、可选 TLS 探针，错误结果脱敏且不返回堆栈。
6. 先写 Controller 测试，验证接口响应、校验和三个独立权限。
7. 实现 Controller 并运行后端目标测试。

### Task 3: 增加权限与前端设备接入抽屉

**Files:**
- Modify: `mine-server/src/main/resources/db/migration/V15__create_device_connection_profile.sql`
- Modify: `mine-web/src/generated/mine-api.ts`
- Modify: `mine-web/src/modules/device/api/device.ts`
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.vue`
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Steps:**
1. 先扩展页面测试，要求有权限时显示“接入配置”，可加载、保存并执行诊断；无权限时不显示操作。
2. 运行 Vitest 并确认失败原因是入口和 API 尚不存在。
3. 增加 API 类型与请求函数。
4. 使用 Naive UI Drawer/Form/Steps 实现接入配置和最近诊断，不添加页面私有样式或硬编码文案。
5. 添加中英文文案与三个按钮权限。
6. 运行页面测试、类型检查和 UI 治理检查。

### Task 4: 验证完整纵向闭环

**Files:**
- Modify: `docs/runbooks/device-onboarding.md`

**Steps:**
1. 更新现场手册，说明页面配置步骤、测试边界和首帧确认要求。
2. 运行 `./mvnw -B -ntp -pl mine-server -am test`。
3. 运行 `pnpm --dir mine-web check:ui-governance`、typecheck、lint、unit test 和 build。
4. 检查 `git diff --check` 与工作区差异，确保没有覆盖用户已有修改。
