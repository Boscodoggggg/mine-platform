# 列表页操作按钮统一实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 参考 SoybeanAdmin 的紧凑按钮层级，统一矿山平台列表页的新增、查询、重置、刷新、列设置和批量删除按钮，同时保留项目暖橙工业主题。

**Architecture:** 新增一个基于 Naive UI `NButton` 的共享列表操作按钮，用 `kind` 固化小尺寸、语义类型、描边层级和图标。列表页只负责传入权限、加载状态、事件与国际化文案；弹窗提交按钮和表格行内文字操作保持现状，避免全局主题覆盖带来的误伤。

**Tech Stack:** Vue 3、TypeScript、Naive UI、UnoCSS、Vitest、Vue Test Utils。

---

### Task 1: 固化列表按钮视觉契约

**Files:**
- Modify: `mine-web/src/modules/device/pages/product-list/ProductListPage.test.ts`
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.test.ts`
- Modify: `mine-web/src/modules/command/pages/command-center/CommandCenterPage.test.ts`
- Modify: `mine-web/src/modules/system/pages/users/UserManagementPage.test.ts`
- Modify: `mine-web/src/modules/system/pages/roles/RoleManagementPage.test.ts`

1. 为新增按钮断言 `size="small"`、主色幽灵层级和图标。
2. 为查询、重置按钮断言 `size="small"` 和图标。
3. 运行上述测试，确认因当前默认中等尺寸且无图标而失败。

### Task 2: 新增共享列表操作按钮

**Files:**
- Create: `mine-web/src/components/common/list-action-button.vue`

1. 支持 `create`、`search`、`reset`、`refresh`、`columns`、`delete` 六种操作。
2. 所有操作固定使用 `small`；新增为主色幽灵按钮，查询为主色实心按钮，删除为错误色幽灵按钮，其余使用普通描边按钮。
3. 每种操作使用既有 Iconify 图标，文案继续由使用页面通过插槽传入。

### Task 3: 迁移列表与工具栏页面

**Files:**
- Modify: `mine-web/src/modules/device/pages/product-list/ProductListPage.vue`
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.vue`
- Modify: `mine-web/src/modules/command/pages/command-center/CommandCenterPage.vue`
- Modify: `mine-web/src/modules/system/pages/users/UserManagementPage.vue`
- Modify: `mine-web/src/modules/system/pages/roles/RoleManagementPage.vue`
- Modify: `mine-web/src/modules/system/pages/menus/MenuManagementPage.vue`
- Modify: `mine-web/src/modules/system/pages/dictionaries/DictionaryManagementPage.vue`
- Modify: `mine-web/src/modules/system/pages/operations/SystemOperationsPage.vue`

1. 替换页面头部新增和刷新按钮。
2. 替换筛选区查询与重置按钮，保持单行紧凑布局。
3. 替换菜单列表工具栏的批量删除、刷新、列设置、新增按钮。
4. 保持弹窗保存/取消、危险确认和表格行内文字按钮不变。

### Task 4: 验证与提交

1. 运行列表页定向测试并确认通过。
2. 运行 `pnpm --dir mine-web check:ui-governance`、`typecheck`、`lint:check`、完整单测和生产构建。
3. 执行代码审查，确认按钮层级、可访问性及共享组件影响。
4. 仅提交本计划和前端按钮改动，提交信息使用中文 Conventional Commits。
