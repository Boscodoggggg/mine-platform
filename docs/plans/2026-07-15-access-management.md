# Access Management Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 mine-platform 增加可在管理端完成的用户、角色和权限分配闭环，同时保持菜单定义由代码只读维护。

**Architecture:** 在 `mine-server` 的 system 模块内增加独立 Controller、Service 和 Mapper，继续使用五表 RBAC、Sa-Token 实时权限和 PostgreSQL 事务；在 `mine-web` 增加用户与角色两个动态页面。成功响应直接返回 DTO，失败继续使用 `ApiErrorResponse`。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring MVC、Sa-Token、MyBatis、PostgreSQL、Flyway、JUnit 5、Testcontainers、Vue 3、TypeScript、Naive UI、Vitest。

---

### Task 1：迁移系统管理菜单契约

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V3__seed_system_access_menus.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Step 1:** 在迁移集成测试中断言系统管理目录、用户/角色页面、十个权限按钮存在且权限码唯一。
**Step 2:** 运行 `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT -DfailIfNoTests=false test`，确认因 V3 缺失而失败。
**Step 3:** 使用固定 UUID 插入菜单节点，`component_key` 分别为 `system-user-list`、`system-role-list`。
**Step 4:** 运行同一测试确认通过，再运行 `./mvnw -pl mine-server -am test`。
**Step 5:** 提交 `feat(auth): 增加系统权限菜单契约`。

### Task 2：定义分页和访问管理持久化接口

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/bootstrap/web/PageResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/repository/AccessManagementMapper.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/repository/AccessManagementMapperIT.java`

**Step 1:** 写 Testcontainers 集成测试覆盖用户/角色分页、关键词、关联 ID 查询、乐观锁更新和角色占用统计。
**Step 2:** 运行目标测试，确认 Mapper 不存在而失败。
**Step 3:** 实现参数化 SQL、批量关联写入、通用操作审计插入；所有 Java 类型添加中文 Javadoc。
**Step 4:** 运行 Mapper 集成测试确认通过。
**Step 5:** 提交 `feat(auth): 增加权限管理数据访问能力`。

### Task 3：实现用户管理服务和 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/service/AccessManagementService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/UserManagementController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/UserManagementControllerTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/AccessManagementIT.java`

**Step 1:** 写失败测试覆盖分页、201 创建、用户名冲突、乐观锁、超级管理员保护、角色替换、密码重置和权限注解。
**Step 2:** 运行 `./mvnw -pl mine-server -Dtest=UserManagementControllerTest -DfailIfNoTests=false test`，确认失败原因是接口未实现。
**Step 3:** 实现用户 DTO、校验、BCrypt、事务、稳定错误码、旧会话注销和脱敏审计。
**Step 4:** 运行单元与集成测试，确认用户管理全绿。
**Step 5:** 提交 `feat(auth): 实现用户与角色分配管理`。

### Task 4：实现角色和只读菜单管理 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/RoleManagementController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/MenuManagementController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/RoleManagementControllerTest.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/system/AccessManagementIT.java`

**Step 1:** 写失败测试覆盖角色创建/修改/删除、编码冲突、占用冲突、乐观锁和菜单原子替换。
**Step 2:** 运行目标测试确认失败。
**Step 3:** 实现角色和菜单 DTO、事务服务、权限注解、审计；角色编码不可修改。
**Step 4:** 运行 `./mvnw -pl mine-server -am verify` 确认后端全绿。
**Step 5:** 提交 `feat(auth): 实现角色与菜单授权管理`。

### Task 5：抽取前端公共 API 客户端

**Files:**
- Create: `mine-web/src/api/client.ts`
- Create: `mine-web/src/api/client.test.ts`
- Modify: `mine-web/src/modules/system/api/auth.ts`

**Step 1:** 写 Vitest 失败测试覆盖直接 DTO、204、统一错误、非 JSON 错误和网络错误。
**Step 2:** 运行 `pnpm --dir mine-web vitest run src/api/client.test.ts` 确认失败。
**Step 3:** 实现 `request<T>` 和 `ApiError`，让 auth API 改为复用且保持现有契约。
**Step 4:** 运行 API 与登录相关测试确认通过。
**Step 5:** 提交 `refactor(frontend): 统一管理端API请求处理`。

### Task 6：实现用户管理页面

**Files:**
- Create: `mine-web/src/modules/system/api/access-management.ts`
- Create: `mine-web/src/modules/system/pages/users/UserManagementPage.vue`
- Create: `mine-web/src/modules/system/pages/users/UserManagementPage.test.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`

**Step 1:** 写失败测试覆盖列表、分页、创建、编辑、角色分配、密码重置、权限按钮和敏感字段清理。
**Step 2:** 运行目标 Vitest 确认页面不存在而失败。
**Step 3:** 使用 Naive UI 实现页面并注册 `system-user-list`。
**Step 4:** 运行类型检查、lint 和目标测试。
**Step 5:** 提交 `feat(frontend): 实现用户与角色分配页面`。

### Task 7：实现角色授权页面

**Files:**
- Create: `mine-web/src/modules/system/pages/roles/RoleManagementPage.vue`
- Create: `mine-web/src/modules/system/pages/roles/RoleManagementPage.test.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`

**Step 1:** 写失败测试覆盖角色 CRUD、占用错误、权限树、按钮权限和并发冲突提示。
**Step 2:** 运行目标 Vitest 确认失败。
**Step 3:** 使用 Naive UI 实现页面并注册 `system-role-list`。
**Step 4:** 运行前端全部测试、类型检查、lint 和构建。
**Step 5:** 提交 `feat(frontend): 实现角色与菜单授权页面`。

### Task 8：联调、文档和 Phase 0 复验

**Files:**
- Modify: `docs/08-security-auth-audit.md`
- Modify: `docs/11-contracts-and-database.md`
- Modify: `docs/reports/phase-0-acceptance.md`
- Modify: `deploy/tests/standalone-smoke.sh`

**Step 1:** 增加真实 HTTPS 登录、改密、创建角色、创建用户、授权、重新请求菜单和禁用用户的冒烟链路。
**Step 2:** 运行 `bash scripts/verify.sh`。
**Step 3:** 运行单机 Compose 核心与可观测性冒烟。
**Step 4:** 确认工作树中的登录页并行改动已独立处理，不混入权限管理提交。
**Step 5:** 更新验收报告并提交 `docs(report): 完成Phase 0权限管理复验`。
