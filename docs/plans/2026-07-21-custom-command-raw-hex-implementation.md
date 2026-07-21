# Custom Command and MQTT RAW_HEX Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 增加版本化产品命令模板，并允许受独立权限保护的管理员通过可靠 Outbox 向在线 MQTT 设备下发最多 4 KB 原始十六进制载荷。

**Architecture:** 命令模板采用草稿、发布和产品绑定控制面；每次执行在 `cmd_instance` 固化载荷快照。消息契约携带载荷格式和 Base64 原始字节，网关对 JSON 保持现有 ACK 状态机，对 RAW_HEX 原样发布并以 `PUBLISHED` 终态结束。

**Tech Stack:** PostgreSQL/Flyway、Java 21、Spring Boot 4、MyBatis、Sa-Token、RabbitMQ、Vue 3、TypeScript、Naive UI、Vitest。

---

### Task 1: 数据库控制面与权限

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V59__create_command_template_control_plane.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Steps:**
1. 在 `DatabaseMigrationIT` 增加失败断言，要求三张模板表、`cmd_instance` 快照字段、`PUBLISHED` 状态约束、菜单权限以及全部中文表/字段注释存在。
2. 运行 `./mvnw -pl mine-server -Dtest=DatabaseMigrationIT test`，确认因 V59 尚不存在而失败。
3. 编写 V59，创建 `cmd_template`、`cmd_template_version`、`cmd_template_binding`，扩展 `cmd_instance`，增加索引、外键、检查约束、中文注释和命令模板菜单权限。
4. 再次运行数据库迁移测试并确认通过。

### Task 2: 十六进制领域校验与状态机

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/command/template/RawHexPayload.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/command/template/RawHexPayloadTest.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/command/model/CommandStatus.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/command/model/CommandInstance.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/command/CommandStateMachineTest.java`

**Steps:**
1. 写失败测试定义 `0xAA bb\n01` 规范化为 `AABB01`、奇数长度/非法字符/空值拒绝、4096 字节接受和 4097 字节拒绝。
2. 运行两个测试类，确认新 API 和 `PUBLISHED` 迁移尚不存在。
3. 最小实现不可变 `RawHexPayload`，使用 Javadoc 中文注释并计算 SHA-256。
4. 增加 `QUEUED -> PUBLISHED` 迁移，并让 `PUBLISHED` 写入发送和完成时间。
5. 重跑测试确认通过。

### Task 3: 模板仓储、服务与 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/command/template/CommandTemplateMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/command/template/CommandTemplateService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/command/template/CommandTemplateController.java`
- Create: `mine-server/src/main/resources/mapper/command/CommandTemplateMapper.xml`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/command/template/CommandTemplateServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/command/template/CommandTemplateControllerTest.java`

**Steps:**
1. 写失败服务测试覆盖创建草稿、编码唯一、RAW_HEX 参数约束、产品绑定、发布、已发布不可修改和乐观锁冲突。
2. 写失败控制器测试覆盖查看、编辑和发布权限。
3. 实现 Mapper 接口与 XML；复杂版本和绑定查询放 XML，短小 CRUD 使用注解。
4. 实现服务、DTO 和控制器，使测试通过。
5. 运行 `./mvnw -pl mine-server -Dtest='*CommandTemplate*Test' test`。

### Task 4: 命令实例载荷快照与临时下发

**Files:**
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/command/service/CommandService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/command/repository/CommandMapper.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/command/repository/StoredCommand.java`
- Modify: command DTO/controller files under `mine-server/src/main/java/com/bosco/mine/server/modules/command/`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/command/CommandOutboxIT.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/command/controller/CommandControllerTest.java`

**Steps:**
1. 写失败测试覆盖模板按设备产品过滤、固定 RAW_HEX 下发、临时 RAW_HEX 独立接口、原因必填、MQTT 模式、在线状态、载荷快照和幂等冲突。
2. 运行测试确认在接口缺失处失败。
3. 扩展命令持久化模型和 Mapper 字段映射。
4. 注入模板服务和设备接入状态服务；实现模板解析及 `POST /api/v1/commands/raw-hex`。
5. 重跑命令服务、控制器和 Outbox 集成测试。

### Task 5: 消息契约和网关原始字节发布

**Files:**
- Modify: `mine-messaging/src/main/java/com/bosco/mine/messaging/command/CommandDispatchPayload.java`
- Modify: `mine-messaging/src/main/java/com/bosco/mine/messaging/command/CommandStatusPayload.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/command/CommandMessageCodec.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/command/MqttCommandPublisher.java`
- Modify: `mine-gateway/src/main/java/com/bosco/mine/gateway/command/CommandDispatchConsumer.java`
- Test: corresponding tests under `mine-messaging/src/test` and `mine-gateway/src/test/java/com/bosco/mine/gateway/command/`

**Steps:**
1. 写失败测试：RAW_HEX Base64 解码后字节完全一致，发布内容类型为 `application/octet-stream`，Broker confirm 后发布 `PUBLISHED`，且 timeout scheduler 调用次数为 0。
2. 保留既有 JSON 用例，证明旧路径行为不变。
3. 扩展向后兼容消息契约和网关分支。
4. 运行 `./mvnw -pl mine-messaging,mine-gateway -am -Dtest='*Command*Test' -Dsurefire.failIfNoSpecifiedTests=false test`。

### Task 6: OpenAPI 和前端 API

**Files:**
- Modify: `docs/openapi/mine-api.yaml`
- Modify: `mine-web/src/modules/command/api/command.ts`
- Generate: `mine-web/src/generated/mine-api.ts`

**Steps:**
1. 先扩展前端契约测试，要求模板 CRUD、发布、按设备查询和临时 RAW_HEX 接口存在。
2. 运行相关 Vitest 确认失败。
3. 更新 OpenAPI 并运行 `pnpm --dir mine-web gen:api`。
4. 实现前端 API 包装并重跑契约测试。

### Task 7: 命令模板页面与高级下发

**Files:**
- Create: `mine-web/src/modules/command/pages/command-templates/CommandTemplatePage.vue`
- Create: `mine-web/src/modules/command/pages/command-templates/CommandTemplatePage.test.ts`
- Modify: `mine-web/src/modules/command/pages/command-center/CommandCenterPage.vue`
- Modify: `mine-web/src/modules/command/pages/command-center/CommandCenterPage.test.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Steps:**
1. 写失败组件测试覆盖草稿创建、RAW_HEX 字节预览、发布确认、权限隐藏、高级下发二次确认和 `PUBLISHED` 标签。
2. 运行命令模块 Vitest，确认缺少页面和入口。
3. 使用 Naive UI 和 UnoCSS 实现页面，不添加业务页 `<style>`、内联 CSS、硬编码颜色或可见文案。
4. 注册动态组件和国际化键。
5. 重跑组件测试和 `pnpm --dir mine-web check:ui-governance`。

### Task 8: 全量验证与提交

**Files:**
- Modify: `docs/04-device-model-strategy.md`
- Modify: `docs/openapi/mine-api.yaml`

**Steps:**
1. 更新物模型/命令文档，明确 RAW_HEX 的 `PUBLISHED` 语义。
2. 运行后端相关模块测试和 `./mvnw -B -ntp verify`（若环境依赖阻塞，记录精确阻塞点）。
3. 运行 `pnpm --dir mine-web typecheck`、`lint:check`、`test:unit`、`build`。
4. 检查 `git diff --check` 和 `git status --short`，确认未覆盖用户已有测试改动。
5. 仅暂存本功能文件并提交：`feat(command): 新增自定义命令与十六进制下发`。
