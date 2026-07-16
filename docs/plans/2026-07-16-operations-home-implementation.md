# Operations Home Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将静态研发占位首页改成使用真实设备和遥测数据的调度首页，并在系统管理下新增独立授权的运行监控页面。

**Architecture:** `mine-server` 提供调度聚合接口与按需运行探测接口；业务库和遥测库分别查询后在应用层合并。`mine-web` 使用 Naive UI、项目主题原子类和 i18n 渲染两个页面，动态菜单继续以数据库 `component_key` 白名单装配。

**Tech Stack:** Java 21、Spring Boot、MyBatis、JdbcTemplate、Sa-Token、Vue 3、TypeScript、Naive UI、Vitest、Vue Test Utils、Playwright、Flyway。

---

### Task 1: 固化调度首页响应契约

**Files:**
- Modify: `docs/openapi/mine-api.yaml`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/dashboard/controller/OperationsDashboardControllerTest.java`

**Steps:**
1. 先写控制器测试，断言 `/api/v1/dashboard/operations` 返回生成时间、数据状态、设备统计、今日消息、24 小时桶和关注设备。
2. 运行定向测试，确认因控制器不存在而失败。
3. 在 OpenAPI 中增加响应模型，明确 `AVAILABLE/UNAVAILABLE`，避免用 0 表示查询失败。
4. 生成前端 TypeScript 类型并确认契约测试通过。

### Task 2: 实现调度聚合查询

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/dashboard/controller/OperationsDashboardController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/dashboard/service/OperationsDashboardService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/dashboard/repository/OperationsDashboardRepository.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/device/repository/DeviceMapper.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/dashboard/service/OperationsDashboardServiceTest.java`

**Steps:**
1. 写失败测试覆盖正常聚合、无设备、遥测库不可用和跨库设备合并。
2. 运行测试并确认失败原因是缺少实现。
3. 实现业务库设备查询和遥测库只读统计；所有 Java 类型与方法补充中文 Javadoc。
4. 使用配置化 5 分钟新鲜度窗口和矿区时区计算当天边界。
5. 单个遥测查询失败时返回 `UNAVAILABLE`，不吞掉业务库结果，也不暴露异常详情。
6. 运行服务与控制器测试至通过。

### Task 3: 用黑盒组件测试定义新首页

**Files:**
- Create: `mine-web/src/views/home/index.test.ts`
- Modify: `mine-web/src/views/home/index.vue`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Steps:**
1. Mock 调度接口，先写失败测试断言运行结论、四项真实指标、关注设备、数据不可用和快捷入口权限行为。
2. 运行 Vitest，确认旧首页因缺少新信息而失败。
3. 实现 API 客户端和新页面；不写色值、私有 `<style>` 或主题模式判断。
4. 删除 `Phase 0`、静态 readiness 和静态 pipeline 数据。
5. 使用语义状态、骨架屏和局部错误处理，确保接口失败不白屏。
6. 运行首页测试、i18n 类型检查和 UI 治理检查。

### Task 4: 固化系统运行监控权限与菜单

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V11__publish_system_operations_monitor.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/system/AccessManagementIT.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/MenuControllerTest.java`

**Steps:**
1. 先写失败测试，断言新菜单位于系统管理下、组件键为 `system-operations-monitor`、权限为 `system:operations:view`。
2. 运行测试并确认迁移缺失导致失败。
3. 添加 Flyway 迁移；不预置普通角色授权，不修改历史迁移。
4. 对迁移新增的菜单契约补充 SQL 注释；本任务不新增数据表。
5. 运行 RBAC 集成测试至通过。

### Task 5: 实现按需系统探测接口

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/SystemOperationsController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/service/SystemOperationsService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/infrastructure/ServiceHealthProbe.java`
- Modify: `mine-server/src/main/resources/application.yml`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/service/SystemOperationsServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/SystemOperationsControllerTest.java`

**Steps:**
1. 先写失败测试覆盖权限拒绝、全部健康、单依赖失败和脱敏响应。
2. 运行测试并确认失败。
3. 实现仅在请求时执行的独立探测；每个远程探测设置连接与请求超时。
4. 所有依赖探测并行执行并受统一短截止时间保护，超时项降级为 `UNKNOWN`。
5. 返回探测名称、状态、检查时间、耗时和安全摘要；不返回连接串、凭据和堆栈。
6. 运行定向测试并检查没有 `@Scheduled`、`setInterval` 或启动扫表逻辑。

### Task 6: 实现运行监控页面与动态菜单白名单

**Files:**
- Create: `mine-web/src/modules/system/pages/operations/SystemOperationsPage.vue`
- Create: `mine-web/src/modules/system/pages/operations/SystemOperationsPage.test.ts`
- Create: `mine-web/src/modules/system/api/operations.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Steps:**
1. 先写路由和页面失败测试，断言系统管理子菜单、状态文字、检查时间、手动刷新和局部故障。
2. 运行测试，确认组件键和页面缺失导致失败。
3. 注册本地组件白名单和 i18n 路由名，禁止根据后端字符串动态 import。
4. 实现服务、消息和存储分组，不自动轮询；点击刷新时重新请求。
5. 运行页面、路由、主题和国际化测试至通过。

### Task 7: 端到端与部署验证

**Files:**
- Create: `mine-web/e2e/operations-home.spec.ts`
- Modify: `docs/07-frontend.md`

**Steps:**
1. 先写 Playwright 用例覆盖首页真实数据、首页到实时监控跳转、系统管理下运行监控和未授权菜单隐藏。
2. 运行用例并确认失败，再补齐必要实现。
3. 执行后端定向测试、前端 typecheck、lint、Vitest、Playwright 和生产构建。
4. 通过 Docker Compose 迁移并验证 `/home` 与 `/system/operations`，确认无定时请求和控制台错误。
5. 复审差异后，使用中文 Conventional Commits 提交。
