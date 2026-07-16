# SoybeanAdmin 风格菜单管理实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将菜单管理调整为 SoybeanAdmin 在线演示的分页表格交互，并在不破坏现有 Sa-Token 权限模型的前提下，将按钮权限从主列表移入页面编辑区域。

**Architecture:** 后端继续以 `sys_menu` 的 `DIRECTORY/MENU/BUTTON` 统一模型保存导航节点和按钮权限，角色授权继续读取完整树。菜单管理主表在前端将完整树投影为仅包含目录和页面的扁平列表并进行本地分页；批量删除通过后端事务接口原子校验和执行。页面编辑时从原树读取直接按钮子节点，并通过现有单节点接口维护。

**Tech Stack:** Vue 3、TypeScript、Naive UI、Vue I18n、Vitest、Spring Boot、Sa-Token、MyBatis、JUnit 5、Mockito、OpenAPI。

---

## 1. 交互边界

- 主表只展示 `DIRECTORY` 和 `MENU`，避免创建、修改、删除等 `BUTTON` 节点形成重复行。
- 主表使用普通分页，默认每页 10 条，可切换 10、20、50 条。
- 保留名称、类型、状态筛选；筛选变化后回到第一页。
- 表格支持多选、原子批量删除、刷新和列设置。
- 目录和页面仍按后端排序及树的先序顺序排列，通过“父菜单”列表达层级。
- 新建和编辑采用双列表单；每个当前可见字段继续提供国际化 Tooltip。
- 仅编辑已存在的页面时显示“按钮权限”，支持新增、编辑和删除按钮权限。
- 新建页面保存后需要重新进入编辑才能配置按钮权限，避免在父节点尚未持久化时制造临时状态。
- 核心节点、含未选中子节点或已被角色引用的节点不得删除。

## 2. 后端批量删除

### Task 1: 先固定原子删除行为

**Files:**

- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/system/service/MenuManagementServiceTest.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/MenuManagementControllerTest.java`

**Steps:**

1. 编写“选中父子节点时按子到父删除”的失败测试。
2. 编写“任一节点受保护时整批不删除”的失败测试。
3. 编写批量删除 HTTP 契约失败测试。
4. 运行定向测试并确认因接口尚不存在而失败。

### Task 2: 实现事务批量删除

**Files:**

- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/MenuManagementController.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/system/service/MenuManagementService.java`
- Modify: `docs/openapi/mine-api.yaml`

**Steps:**

1. 新增 `POST /api/v1/system/menus/batch-delete`，限制 1 至 100 个菜单标识。
2. 在同一事务内读取完整菜单快照并验证节点存在、非核心、无角色引用。
3. 验证每个待删节点不存在批次以外的直接子节点。
4. 根据节点深度从深到浅删除，保证父子节点同批删除时不违反外键。
5. 为每个删除节点记录操作审计。
6. 运行定向后端测试确认通过。

## 3. 前端菜单管理

### Task 3: 先固定页面行为

**Files:**

- Modify: `mine-web/src/modules/system/pages/menus/MenuManagementPage.test.ts`

**Steps:**

1. 将原“树默认折叠”测试改为“主表不展示按钮权限”。
2. 编写分页、页容量和过滤复位测试。
3. 编写多选批量删除调用测试。
4. 编写编辑页面时按钮权限分区及新增按钮测试。
5. 保留所有可见菜单表单字段具有 Tooltip 的测试。
6. 运行定向前端测试并确认因交互尚未实现而失败。

### Task 4: 实现页面投影与工具栏

**Files:**

- Modify: `mine-web/src/modules/system/pages/menus/MenuManagementPage.vue`
- Modify: `mine-web/src/modules/system/api/access-management.ts`

**Steps:**

1. 将菜单树转换为带父菜单名称的目录/页面扁平投影。
2. 增加客户端分页状态和分页数据计算。
3. 增加多选、批量删除、刷新和列设置。
4. 使用 Naive UI 组件和主题变量，不新增硬编码颜色或页面级 CSS。
5. 保留组件白名单选择，禁止数据库字符串直接动态导入页面。

### Task 5: 实现按钮权限编辑

**Files:**

- Modify: `mine-web/src/modules/system/pages/menus/MenuManagementPage.vue`

**Steps:**

1. 编辑 `MENU` 节点时读取其直接 `BUTTON` 子节点。
2. 在同一弹窗中增加按钮权限列表和渐进式编辑区。
3. 按钮字段包括名称、权限码、排序和状态，且全部提供 Tooltip。
4. 新增、更新和删除后重新加载菜单树，但保持当前菜单编辑上下文。
5. 核心按钮不展示删除操作。

## 4. 国际化、契约和验证

### Task 6: 补齐契约与语言资源

**Files:**

- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/typings/app.d.ts`
- Regenerate: `mine-web/src/generated/mine-api.ts`
- Modify: `docs/plans/2026-07-16-management-model-command-design.md`

**Steps:**

1. 增加分页、批量删除、列设置和按钮权限相关中英文文案。
2. 更新国际化键类型。
3. 更新 OpenAPI 并重新生成前端类型。
4. 将“菜单树不分页”的旧结论修订为“授权树不分页、菜单维护投影分页”。

### Task 7: 完整验证和提交

**Steps:**

1. 运行菜单管理前后端定向测试。
2. 运行后端模块测试。
3. 运行前端全量测试、类型检查和规范检查。
4. 运行前端生产构建。
5. 执行 `git diff --check` 并人工检查危险操作、国际化和主题约束。
6. 只暂存本计划涉及的菜单文件，不包含工作区已有的字典和运行监控改动。
7. 使用中文 Conventional Commit 提交。
