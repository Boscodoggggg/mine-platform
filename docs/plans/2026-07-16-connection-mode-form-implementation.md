# Device Connection Mode Form Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 让设备接入配置表单按连接方式切换字段语义、默认值和独立草稿。

**Architecture:** 保持现有 API 契约不变，在 `DeviceListPage` 内根据 `connectionMode` 计算表单展示模型，并用按模式索引的草稿快照保留切换前输入。所有可见文案继续由 Vue I18n 管理。

**Tech Stack:** Vue 3 Composition API、TypeScript、Naive UI、Vue I18n、Vitest、Vue Test Utils。

---

### Task 1: 以失败测试定义模式化表单

**Files:**
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.test.ts`

1. 新增测试，切换至设备主动 TCP 时断言平台监听地址、9000 端口、TLS 默认关闭和应用心跳文案。
2. 新增测试，切换至平台主动 TCP 时断言设备地址语义。
3. 新增测试，切回 MQTT 时断言恢复切换前草稿。
4. 运行 `pnpm --dir mine-web exec vitest run src/modules/device/pages/device-list/DeviceListPage.test.ts`，确认因切换入口和展示模型不存在而失败。

### Task 2: 实现模式默认值与草稿切换

**Files:**
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.vue`

1. 新增连接模式类型、模式草稿 Map 和默认值工厂。
2. 新增 `switchConnectionMode`，先保存当前表单快照，再恢复目标模式草稿或默认值。
3. 配置加载和保存成功后同步当前模式草稿。
4. 实现展示模型 computed，提供说明、主机、端口、超时、保活标签及占位文案。

### Task 3: 调整模板和国际化

**Files:**
- Modify: `mine-web/src/modules/device/pages/device-list/DeviceListPage.vue`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Modify: `mine-web/src/typings/app.d.ts`

1. 选择器改为通过 `switchConnectionMode` 更新，在选择器下展示当前模式说明。
2. 把主机、端口、超时和保活标签替换为动态文案。
3. 补齐中英文 locale 与 `App.I18n.Schema` 类型。
4. 运行目标测试，确认绿灯。

### Task 4: 质量门禁与提交隔离

**Files:**
- Test: `mine-web/src/modules/device/pages/device-list/DeviceListPage.test.ts`
- Test: `mine-web/src/test/ui-governance.test.ts`

1. 运行 `pnpm --dir mine-web check:ui-governance`、`typecheck`、`lint:check`、`test` 和 `build`。
2. 运行 `git diff --check`，确认新页面无私有样式、颜色或硬编码文案。
3. 只暂存本功能的计划、页面、测试和国际化 hunk，不包含当前工作区的协议发布、首页和路由修改。
