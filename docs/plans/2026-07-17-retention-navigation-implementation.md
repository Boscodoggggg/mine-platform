# Data Retention Navigation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将已完成的数据保留能力发布为“数据治理 → 数据保留”独立菜单，同时复用现有策略工作台内核。

**Architecture:** 使用 V28 Flyway 迁移发布后端动态菜单与兼容角色授权，角色服务原子补齐治理入口所需的最小读取依赖；动态路由为复用的策略工作台注入固定 `RETENTION` 属性，并注册独立组件白名单键。原策略工作台、编辑发布权限边界和 API 契约保持兼容。

**Tech Stack:** PostgreSQL/Flyway、Spring Boot 集成测试、Vue 3、TypeScript、Naive UI、Vue I18n、Vitest、Playwright。

---

### Task 1: 固定类型的数据保留页面

**Files:**
- Modify: `mine-web/src/modules/device/pages/strategy/StrategyWorkbenchPage.vue`
- Modify: `mine-web/src/modules/device/pages/strategy/StrategyWorkbenchPage.test.ts`

1. 先写失败测试，要求固定模式初始加载 `RETENTION`、隐藏策略类型选择器并显示数据治理标题。
2. 运行定向 Vitest，确认因页面或属性缺失而失败。
3. 为策略工作台增加可选 `fixedStrategyType` 属性，由独立动态路由传入 `RETENTION`，并在首屏加载最近执行事实。
4. 重跑定向测试，确认通用策略工作台行为没有回退。

### Task 2: 发布后端动态菜单

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V28__publish_retention_governance_navigation.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

1. 先扩展迁移集成测试，要求存在“数据治理”目录、`retention-governance` 页面、独立导航权限和旧策略角色兼容授权。
2. 运行 `DatabaseMigrationIT`，确认测试因 V28 缺失而失败。
3. 编写 V28 迁移，插入受保护目录和菜单，并为已有 `device:strategy:view` 角色复制导航授权及产品、设备查看依赖。
4. 重跑迁移测试，并再次执行迁移验证幂等性。

### Task 3: 注册路由和国际化菜单

**Files:**
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/typings/app.d.ts`

1. 先写失败测试，输入后端数据治理菜单后应生成已知路由和中英文导航。
2. 注册 `retention-governance` 白名单键、路径回退国际化键和菜单文案。
3. 运行路由测试、typecheck、lint 与 UI 治理检查。

### Task 4: 浏览器闭环与提交

**Files:**
- Modify: `mine-web/e2e/retention-strategy.spec.ts`

1. 先把 E2E 入口改为点击“数据治理 → 数据保留”，确认旧实现无法找到菜单。
2. 更新模拟 `/auth/menus` 契约并验证专属页面直接加载保留策略。
3. 运行前后端定向测试、前端全量单测、生产构建和 Playwright。
4. 提交 `feat(governance): 发布数据保留独立导航`。
