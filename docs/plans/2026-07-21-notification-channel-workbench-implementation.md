# Notification Channel Workbench Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 发布可见的通知渠道管理页面，并让授权管理员在编辑页受审计地回显数据库密文中的真实 Provider 凭据。

**Architecture:** 普通查询继续返回安全摘要；新增独立 secrets 读取接口，以单独权限、事务审计和禁用缓存保护明文。前端从 Provider 目录动态生成表单，通过数据库菜单和本地组件白名单发布路由。

**Tech Stack:** Java 21、Spring Boot、Sa-Token、MyBatis、PostgreSQL/Flyway、OpenAPI、Vue 3、TypeScript、Naive UI、Vue I18n、Vitest。

---

### Task 1: 敏感凭据读取契约

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationChannelSecretsResponse.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationChannelService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationChannelController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationChannelServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationChannelControllerTest.java`

1. 先写失败测试，要求 secrets 接口使用 `notification:channel:secret:view`、返回禁用缓存响应，并在解密后写入不含值的读取审计。
2. 运行目标测试，确认因接口和 DTO 尚不存在而失败。
3. 实现最小 DTO、服务方法和控制器端点；安全 `toString()` 不输出 secrets。
4. 重跑目标测试并确认通过。

### Task 2: OpenAPI 与数据库菜单

**Files:**
- Modify: `docs/openapi/mine-api.yaml`
- Create: `mine-server/src/main/resources/db/migration/V57__publish_notification_channel_workbench.sql`
- Modify: `mine-web/src/generated/mine-api.ts`（通过生成脚本）

1. 在 OpenAPI 中增加 `GET /notifications/channels/{channelId}/secrets` 和只读响应契约。
2. 新增 V57 菜单迁移，发布 `/alarm/channels`、重挂现有按钮并新增敏感查看权限。
3. 执行 `pnpm --dir mine-web gen:api`，确认生成类型包含新端点。
4. 运行 OpenAPI 与真实 PostgreSQL 迁移测试。

### Task 3: 前端 API 与动态导航

**Files:**
- Create: `mine-web/src/modules/notification/api/notification.ts`
- Create: `mine-web/src/modules/notification/api/notification.test.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.test.ts`

1. 先写 API 路径、HTTP 方法和菜单组件键的失败测试。
2. 实现渠道目录、列表、详情、secrets、创建和更新调用，以及动态组件白名单与国际化路由映射。
3. 运行目标 Vitest，确认通过。

### Task 4: 通知渠道管理页面

**Files:**
- Create: `mine-web/src/modules/notification/pages/channel-management/NotificationChannelManagementPage.vue`
- Create: `mine-web/src/modules/notification/pages/channel-management/NotificationChannelManagementPage.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/typings/app.d.ts`

1. 先写页面失败测试：加载渠道、权限控制、编辑时回显 secrets、无权限不读取、保存时提交动态字段。
2. 实现 Naive UI 表格和抽屉表单，根据 Provider 元数据渲染 STRING、INTEGER、BOOLEAN、STRING_LIST、SECRET 字段。
3. 所有文案进入中英文资源；敏感字段使用可切换密码输入框，普通响应不显示明文。
4. 运行页面测试和 UI 治理检查。

### Task 5: 回归、浏览器验收与提交

1. 运行后端目标测试、完整 Maven 测试、真实 PostgreSQL 迁移测试。
2. 运行前端 typecheck、lint、unit test、build 和 `check:ui-governance`。
3. 启动或复用本地服务，以管理员账号验证菜单、列表、创建抽屉和敏感回显入口；不调用真实外部 Provider。
4. 执行 `git diff --check` 和代码审查，只暂存本轮文件。
5. 使用中文 Conventional Commit 提交。
