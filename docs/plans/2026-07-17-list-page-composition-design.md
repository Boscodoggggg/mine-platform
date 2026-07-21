# 标准列表页结构对齐实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将标准列表页从“页面标题 + 常驻筛选 + 裸表格”调整为 SoybeanAdmin 风格的“可折叠搜索卡片 + 带标题和工具栏的列表卡片”。

**Architecture:** 新增共享 `ListSearchPanel` 负责默认收起的搜索区域，列表页使用 Naive UI `NCard` 的标题和 `header-extra` 组织新增、刷新操作。保留现有查询模型、API、分页、弹窗和表格行内操作，只改变信息编排，不新增无后端能力支撑的批量删除或列设置。

**Tech Stack:** Vue 3、TypeScript、Naive UI、UnoCSS、Vue I18n、Vitest。

---

### Task 1: 固化结构契约

**Files:**
- Modify: `mine-web/src/modules/system/pages/roles/RoleManagementPage.test.ts`
- Modify: `mine-web/src/modules/system/pages/users/UserManagementPage.test.ts`
- Modify: `mine-web/src/modules/device/pages/product-list/ProductListPage.test.ts`
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.test.ts`
- Modify: `mine-web/src/modules/command/pages/command-center/CommandCenterPage.test.ts`

1. 断言页面存在独立搜索面板和列表卡片。
2. 断言搜索面板默认收起，展开后筛选控件仍在单行容器。
3. 断言新增和刷新位于列表卡片工具栏，而不是页面标题区域。
4. 运行定向测试并确认结构断言失败。

### Task 2: 实现共享搜索面板

**Files:**
- Create: `mine-web/src/components/common/list-search-panel.vue`
- Create: `mine-web/src/components/common/list-search-panel.test.ts`
- Modify: `mine-web/src/typings/components.d.ts`

1. 使用 `NCard`、`NCollapse`、`NCollapseItem` 组合默认收起的搜索面板。
2. 保留键盘操作和 Naive UI 原生展开状态反馈。
3. 通过默认插槽承载各页面现有筛选表单。

### Task 3: 迁移标准列表页

**Files:**
- Modify: `mine-web/src/modules/system/pages/roles/RoleManagementPage.vue`
- Modify: `mine-web/src/modules/system/pages/users/UserManagementPage.vue`
- Modify: `mine-web/src/modules/device/pages/product-list/ProductListPage.vue`
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.vue`
- Modify: `mine-web/src/modules/command/pages/command-center/CommandCenterPage.vue`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/typings/app.d.ts`

1. 移除内容区重复的页面标题条。
2. 将现有筛选控件放入 `ListSearchPanel`，展开时保持一行。
3. 为数据卡片增加“用户列表、角色列表、产品列表、设备列表、命令列表”标题。
4. 将新增和刷新按钮集中到卡片 `header-extra` 工具栏。

### Task 4: 验证

1. 运行共享组件和五个页面定向测试。
2. 运行 UI 治理、typecheck、lint、完整单测和生产构建。
3. 审查布局覆盖、国际化、可访问性和工作区差异。
