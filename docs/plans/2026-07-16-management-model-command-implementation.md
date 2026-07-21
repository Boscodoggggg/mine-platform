# System Configuration, Device Model, and Command Management Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 增加可安全前台维护的菜单与字典、版本化设备字段映射，以及面向生产的命令模板和审批能力。

**Architecture:** 系统配置、设备定义和命令管理保持三个边界。普通系统配置即时生效；物模型、协议映射和命令模板采用不可变发布版本。所有写操作使用 Sa-Token 权限、乐观锁和审计，生产命令继续经过 Outbox、RabbitMQ 和设备 ACK 状态机。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis、PostgreSQL、Sa-Token、Flyway、RabbitMQ、Vue 3、TypeScript、Naive UI、Vitest、Playwright、OpenAPI。

## 交付状态（2026-07-16）

| 任务 | 状态 | 实际交付 |
| --- | --- | --- |
| Task 1-4 | 已完成 | V12、菜单 CRUD/组件白名单/保护校验、字典 CRUD/精准缓存失效、Vue 页面、权限与国际化 |
| Task 5-6 | 已完成核心闭环 | V13、物模型与协议映射草稿/原子保存/发布、OpenAPI 和 Vue 联合管理页；样本解析测试与回滚后续补充 |
| Task 7 | 部分完成 | 当前采用服务端审查发布的 LOW 模板目录和 JSON Schema；数据库模板、产品能力绑定尚未开放 |
| Task 8 | 已完成 | V14、原因/作业单、动态参数、模板筛选、排队命令与 Outbox 原子取消、审计和详情展示 |
| Task 9 | 未开始 | MEDIUM/HIGH 审批和在线新鲜度联锁未开放，当前所有可见模板均为 LOW |
| Task 10 | 本轮门禁已完成 | 后端 verify、前端 typecheck/lint/unit/build 均通过；现场 Compose 升级和真实设备 E2E 在部署验收时执行 |

实现与原计划有意保持一个安全差异：命令模板前台发布必须等审批、设备能力绑定和协议编码器白名单一起完成，不能先开放一个能绕过生产控制的“任意命令配置器”。

---

### Task 1: 系统配置数据库与权限

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V12__create_menu_and_dictionary_management.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Steps:**
1. 先在迁移集成测试中断言菜单扩展字段、`sys_dict_type`、`sys_dict_item`、索引、中文注释和权限节点存在。
2. 运行 `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT verify`，确认因 V12 缺失而失败。
3. 创建迁移：给 `sys_menu` 增加 `i18n_key/protected_node`，建立两张字典表，发布菜单/字典页面和按钮权限。
4. 再次运行迁移测试，确认通过。

### Task 2: 菜单管理后端

**Files:**
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/MenuManagementController.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/system/service/AccessManagementService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/system/repository/AccessManagementMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/dto/MenuSaveRequest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/MenuManagementControllerTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/service/MenuManagementServiceTest.java`

**Steps:**
1. 写失败测试覆盖创建目录/菜单/按钮、组件白名单、权限唯一、循环父子、受保护删除、角色引用删除和乐观锁。
2. 运行定向测试确认 RED。
3. 实现 `GET/POST/PUT/DELETE /api/v1/system/menus` 与 `/component-options`。
4. 记录前后快照审计；成功写入后使当前权限快照失效。
5. 运行定向测试确认 GREEN。

### Task 3: 字典管理后端

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/DictionaryController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/service/DictionaryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/repository/DictionaryMapper.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/service/DictionaryServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/system/DictionaryIT.java`

**Steps:**
1. 写失败测试覆盖类型/数据 CRUD、稳定编码、默认项唯一、停用、语义颜色白名单、乐观锁和审计。
2. 运行定向测试确认 RED。
3. 实现 `/api/v1/system/dictionaries` 和 `/api/v1/dictionaries/{typeCode}/items`。
4. 使用按类型精准失效的 Caffeine/本地缓存，不增加定时刷新。
5. 运行测试确认 GREEN。

### Task 4: 菜单与字典前端

**Files:**
- Create: `mine-web/src/modules/system/pages/menus/MenuManagementPage.vue`
- Create: `mine-web/src/modules/system/pages/dictionaries/DictionaryManagementPage.vue`
- Create: `mine-web/src/modules/system/api/menu-management.ts`
- Create: `mine-web/src/modules/system/api/dictionary.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Test: matching `*.test.ts` and `mine-web/e2e/system-configuration.spec.ts`

**Steps:**
1. 写失败组件测试，覆盖权限按钮、树编辑、受保护节点、字典类型/数据切换和错误状态。
2. 实现中等密度工业管理页；全部颜色和样式来自主题与 Naive UI。
3. 增加动态组件白名单和国际化菜单。
4. 运行 Vitest、主题治理、typecheck 和 Playwright。

### Task 5: 物模型与字段映射数据库

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V13__create_model_and_protocol_field_mapping.sql`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Steps:**
1. 先断言模型、版本、属性、协议字段和映射表及全部中文注释。
2. 创建带草稿/发布状态、内容哈希、版本唯一和引用完整性的迁移。
3. 验证迁移升级和全新安装两种路径。

### Task 6: 物模型字段与映射后端/前端

**Files:**
- Create: `mine-server/.../modules/model/**`
- Create: `mine-server/.../modules/protocol/**`
- Create: `mine-web/src/modules/device/pages/model-fields/**`
- Create: `mine-web/src/modules/device/pages/field-mappings/**`
- Modify: `docs/openapi/mine-api.yaml`

**Steps:**
1. 用失败测试定义草稿、复制新版本、字段校验、样本测试、发布和回滚行为。
2. 实现领域服务和只读运行时快照。
3. 实现属性表格编辑器、映射编辑器、样本测试和发布差异页面。
4. 生成 OpenAPI 类型并运行后端/前端/E2E 测试。

### Task 7: 命令模板与能力绑定

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V14__create_command_templates.sql`
- Replace: `mine-server/src/main/java/com/bosco/mine/server/modules/command/service/PublishedCommandTemplates.java`
- Create: `mine-server/.../modules/command/template/**`
- Create: `mine-web/src/modules/command/pages/templates/**`

**Steps:**
1. 写失败测试覆盖模板草稿、JSON Schema 白名单、版本发布、产品绑定、回复字段白名单和已发布不可修改。
2. 实现数据库模板仓储，保留 `QUERY_STATUS` 为种子模板。
3. 命令模板查询按设备当前产品和生效版本过滤。
4. 实现模板管理页面、版本历史和发布确认。

### Task 8: 命令下发增强

**Files:**
- Modify: `mine-server/src/main/resources/db/migration/V9__create_command_instance.sql` via new V15 migration
- Modify: `CommandCreateRequest.java`, `CommandController.java`, `CommandService.java`
- Create: `POST /api/v1/commands/{id}/cancel`
- Modify: `mine-web/src/modules/command/pages/command-center/CommandCenterPage.vue`
- Create: `mine-web/src/modules/command/components/SchemaCommandForm.vue`

**Steps:**
1. 写失败测试覆盖原因必填、工单号、动态参数校验、设备能力拒绝、取消状态机和增强筛选。
2. 实现后端最终校验和审计，不信任前端 Schema 表单。
3. 实现动态参数、在线状态、绝对过期时间、取消和筛选 UI。
4. 覆盖成功、拒绝、超时、取消和响应丢失幂等 E2E。

### Task 9: 中高风险审批与联锁

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V16__create_command_approval.sql`
- Create: `mine-server/.../modules/command/approval/**`
- Create: `mine-web/src/modules/command/pages/approvals/**`

**Steps:**
1. 写失败测试覆盖提交人与审批人不能相同、MEDIUM/HIGH 权限、设备在线新鲜度、禁止离线排队、禁止自动重试和审批过期。
2. 实现审批状态机和一次性 RabbitMQ 截止消息，不增加数据库轮询。
3. 实现待办列表、风险摘要、拒绝原因和双人确认。
4. 完成安全审计与 E2E。

### Task 10: 全量验证和部署

**Steps:**
1. 运行 `./mvnw -B -ntp verify`。
2. 运行 `pnpm typecheck && pnpm lint:check && pnpm vitest run && pnpm build`。
3. 运行全部 Playwright。
4. 用 Compose 升级现场库，确认只执行 V12-V16 增量迁移且服务 healthy。
5. 更新架构、数据库、权限、OpenAPI 和运维文档。
6. 按中文 Conventional Commits 分阶段提交。
