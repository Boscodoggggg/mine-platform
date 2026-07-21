# Alarm Notification and Escalation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付短信、语音、企业 IM、邮件、Webhook、告警事件单、升级策略和可信送达状态的完整非现场自动化闭环。

**Architecture:** 在 `mine-server` 内建立独立通知领域、版本化控制面和数据库租约工作器，通过稳定 SDK 接入不同供应商。告警入库事务原子创建通知事件和首批投递，外部调用、异步回执、升级步骤和事件单分别使用幂等账本收敛；模块边界允许后续拆成独立服务。

**Tech Stack:** Java 21、Spring Boot 4、PostgreSQL、Flyway、MyBatis/JdbcTemplate、AES-256-GCM、Jakarta Mail、JDK HTTP Client、Vue 3、Naive UI、UnoCSS、Vue I18n、Vitest、Testcontainers。

---

### Task 1: 定义稳定通知适配器 SDK

**Files:**
- Modify: `pom.xml`
- Create: `mine-notification-sdk/pom.xml`
- Create: `mine-notification-sdk/src/main/java/com/bosco/mine/notification/sdk/NotificationChannelAdapter.java`
- Create: `mine-notification-sdk/src/main/java/com/bosco/mine/notification/sdk/NotificationChannelType.java`
- Create: `mine-notification-sdk/src/main/java/com/bosco/mine/notification/sdk/NotificationAdapterKey.java`
- Create: `mine-notification-sdk/src/main/java/com/bosco/mine/notification/sdk/NotificationDeliveryRequest.java`
- Create: `mine-notification-sdk/src/main/java/com/bosco/mine/notification/sdk/NotificationDeliveryResult.java`
- Create: `mine-notification-sdk/src/main/java/com/bosco/mine/notification/sdk/NotificationAdapterException.java`
- Create: `mine-notification-sdk/src/main/java/com/bosco/mine/notification/sdk/package-info.java`
- Test: `mine-notification-sdk/src/test/java/com/bosco/mine/notification/sdk/NotificationAdapterContractTest.java`

**Step 1: Write the failing test**

定义期望 API：适配器键为 `channelType + provider`，请求对象递归复制模板变量和配置，结果只能为 `SUBMITTED/DELIVERED/RETRYABLE_FAILURE/PERMANENT_FAILURE/UNKNOWN`。测试拒绝空目标、超长标题/正文、非法供应商消息标识和 `UNKNOWN` 携带自动重试时间。

**Step 2: Run test to verify it fails**

Run: `./mvnw -B -ntp -pl mine-notification-sdk -am -Dtest=NotificationAdapterContractTest test`

Expected: FAIL，模块或通知 SDK 类型尚不存在。

**Step 3: Write minimal implementation**

所有公开类型和方法补中文 Javadoc。SDK 只依赖 JDK，不暴露 Spring、数据库、RabbitMQ、Sa-Token 或供应商 SDK。

**Step 4: Run test to verify it passes**

Run: `./mvnw -B -ntp -pl mine-notification-sdk -am -Dtest=NotificationAdapterContractTest test`

Expected: PASS。

**Step 5: Commit**

```bash
git add pom.xml mine-notification-sdk
git commit -m "feat(notification): 定义通知渠道适配器契约"
```

### Task 2: 建立通知配置与发布控制面表

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V53__create_notification_control_plane.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Step 1: Write the failing migration test**

把以下表加入预期清单并逐表校验表注释、全部字段注释、状态约束、外键和唯一键：

```text
notification_channel
notification_recipient_group
notification_recipient
notification_template
notification_policy
notification_policy_version
notification_policy_step
```

额外校验发布版本不可变所需的状态和版本约束，以及接收目标不允许同时保存明文与密文。

**Step 2: Run test to verify it fails**

Run: `./mvnw -B -ntp -pl mine-server -am -Dtest=DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，新表不存在。

**Step 3: Implement migration**

使用 `COMMENT ON TABLE` 和 `COMMENT ON COLUMN` 补齐每个注释。渠道凭据和接收目标只存密文、摘要和脱敏值；策略版本保存匹配条件、确认/恢复行为，步骤保存延迟、渠道、接收组、模板和事件单动作。

**Step 4: Run test to verify it passes**

Run: `./mvnw -B -ntp -pl mine-server -am -Dtest=DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/resources/db/migration/V53__create_notification_control_plane.sql mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java
git commit -m "feat(notification): 建立通知配置与发布控制面"
```

### Task 3: 实现通知密钥与敏感配置边界

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/security/NotificationSecretCipher.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/security/AesGcmNotificationSecretCipher.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/security/NotificationSecretProperties.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/security/NotificationSecretCipherException.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/security/package-info.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/security/AesGcmNotificationSecretCipherTest.java`
- Modify: `mine-server/src/main/resources/application.yml`
- Modify: `mine-server/src/test/resources/application-test.yml`

**Step 1: Write the failing tests**

测试相同明文产生不同密文、正确解密、渠道标识/用途/配置版本作为 AAD、防篡改、错误密钥、未知版本、非 256 位密钥和密钥文件缺失。测试异常不得包含明文。

**Step 2: Verify RED**

Run: `./mvnw -B -ntp -pl mine-server -am -Dtest=AesGcmNotificationSecretCipherTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，加密器尚不存在。

**Step 3: Implement minimal cipher**

使用独立 `mine.notification.security.key-file`，密文格式固定为版本、随机数和带认证标签密文。测试环境使用独立 classpath 密钥，生产只允许外部 secret 文件。

**Step 4: Verify GREEN**

Run: `./mvnw -B -ntp -pl mine-server -am -Dtest=AesGcmNotificationSecretCipherTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/notification/security mine-server/src/main/resources/application.yml mine-server/src/test/resources/application-test.yml mine-server/src/test/java/com/bosco/mine/server/modules/notification/security
git commit -m "feat(notification): 加密通知渠道和接收目标"
```

### Task 4: 实现渠道、接收组和模板管理 API

**Files:**
- Modify: `mine-server/pom.xml`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/repository/NotificationControlMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationControlService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationControlController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationChannelRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationChannelResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationRecipientGroupRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationTemplateRequest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationControlServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationControlControllerTest.java`
- Modify: `docs/openapi/mine-api.yaml`

**Steps:**

1. 先写服务和控制器失败测试，覆盖精确 RBAC、类型/供应商组合、密文不出 API、敏感查看权限、模板变量白名单、乐观锁、审计和禁用被引用渠道拒绝。
2. Run: `./mvnw -B -ntp -pl mine-server -am -Dtest=NotificationControlServiceTest,NotificationControlControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认 RED。
3. 实现简单 CRUD 注解 Mapper；涉及多条件引用检查的复杂查询放 XML。
4. 更新 OpenAPI 并生成前端类型。
5. 重跑定向测试，确认 GREEN。
6. Commit: `feat(notification): 提供通知配置管理接口`。

### Task 5: 实现不可变升级策略发布

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/domain/NotificationPolicyDefinition.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/repository/NotificationPolicyMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationPolicyService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationPolicyController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/domain/NotificationPolicyDefinitionTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationPolicyServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationPolicyControllerTest.java`
- Modify: `docs/openapi/mine-api.yaml`

**Steps:**

1. 先测试等级/矿区/规则匹配、步骤稳定排序、延迟单调、重复渠道目标、未启用依赖、无触达步骤、发布幂等和已发布版本不可改。
2. 验证 RED。
3. 实现草稿、校验、发布、归档和按告警事实选择唯一最高优先级策略；同优先级重叠策略阻止发布。
4. 验证 GREEN，并运行数据库集成测试。
5. Commit: `feat(notification): 发布版本化告警升级策略`。

### Task 6: 建立投递、回执和事件单运行账本

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V54__create_notification_delivery_runtime.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Steps:**

1. 先把 `notification_incident`、`notification_delivery`、`notification_delivery_attempt`、`notification_receipt`、`notification_ticket`、`notification_ticket_history` 加入迁移测试，校验全字段中文注释、状态机约束、租约一致性、幂等唯一键和不可变历史。
2. 验证 RED。
3. 实现 V54 迁移；事件单不引用生产 `mine_work_order`。
4. 验证 GREEN。
5. Commit: `feat(notification): 建立通知投递与事件单账本`。

### Task 7: 在告警事务中创建通知事件

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/repository/NotificationIncidentMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationIncidentService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/alarm/service/AlarmEventService.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/alarm/service/AlarmEventServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/NotificationIncidentIT.java`

**Steps:**

1. 先测试 raised 与通知事件同事务、重复/恢复早到不重复、策略版本固定、无匹配策略、恢复通知和确认停止未来升级。
2. 验证 RED。
3. 实现事务内事件、首步投递和可选事件单创建；数据库写失败必须让告警消息 NACK。
4. 验证 GREEN。
5. Commit: `feat(notification): 从告警事实创建通知升级事件`。

### Task 8: 实现租约投递工作器与恢复

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/runtime/NotificationDeliveryRepository.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/runtime/NotificationAdapterRegistry.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/runtime/NotificationDeliveryWorker.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/runtime/NotificationDeliveryRecovery.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/runtime/NotificationRuntimeProperties.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/runtime/NotificationRuntimeConfiguration.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/runtime/NotificationAdapterRegistryTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/runtime/NotificationDeliveryWorkerTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/runtime/NotificationDeliveryRepositoryIT.java`

**Steps:**

1. 先测试 SPI 重复键、`SKIP LOCKED`、租约、尝试追加、安全重试、计费渠道超时转 UNKNOWN、退避上限、失联恢复和未知结果不盲重发。
2. 验证 RED。
3. 实现有界调度器和独立执行器。
4. 验证 GREEN。
5. Commit: `feat(notification): 执行可靠通知投递与恢复`。

### Task 9: 实现异步回执与送达查询 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationReceiptService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationReceiptController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationDeliveryQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationDeliveryController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationReceiptServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationDeliveryControllerTest.java`
- Modify: `docs/openapi/mine-api.yaml`

**Steps:**

1. 先测试回执签名、批量上限、重复/乱序、终态不回退、未知消息、固定响应、送达筛选、人工重试/取消幂等和审计。
2. 验证 RED。
3. 实现阿里云 HTTP 回执规范化入口和统一查询/操作 API。
4. 验证 GREEN。
5. Commit: `feat(notification): 接收回执并查询送达状态`。

### Task 10: 实现告警事件单 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/domain/NotificationTicketLifecycle.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/repository/NotificationTicketMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationTicketService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationTicketController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/domain/NotificationTicketLifecycleTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationTicketServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationTicketControllerTest.java`
- Modify: `docs/openapi/mine-api.yaml`

**Steps:**

1. 先测试领取、转派、开始、解决、关闭、取消、非法跳转、SLA、作用域、幂等、乐观锁和不可变历史。
2. 验证 RED。
3. 实现独立事件单领域和 API。
4. 验证 GREEN。
5. Commit: `feat(notification): 建立告警事件单处置闭环`。

### Task 11: 实现 SMTP、企业 IM 与通用 Webhook 适配器

**Files:**
- Modify: `mine-server/pom.xml`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/SmtpNotificationAdapter.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/WeComRobotNotificationAdapter.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/DingTalkRobotNotificationAdapter.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/FeishuRobotNotificationAdapter.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/GenericWebhookNotificationAdapter.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/SafeWebhookTargetPolicy.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/adapter/NotificationWebhookAdapterTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/adapter/SmtpNotificationAdapterTest.java`

**Steps:**

1. 先使用本地 HTTP/SMTP 测试服务覆盖签名、超时、状态映射、消息上限、HTTPS/域名允许列表、DNS 重绑定、私网/元数据地址拒绝和敏感日志边界。
2. 验证 RED。
3. 实现官方消息格式；机器人和 SMTP 成功只返回 `SUBMITTED`。
4. 验证 GREEN。
5. Commit: `feat(notification): 接入邮件企业 IM 与 Webhook`。

### Task 12: 实现阿里云短信与语音适配器

**Files:**
- Modify: `mine-server/pom.xml`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/AliyunSmsNotificationAdapter.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/AliyunVoiceNotificationAdapter.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/adapter/AliyunSmsNotificationAdapterTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/adapter/AliyunVoiceNotificationAdapterTest.java`

**Steps:**

1. 先测试 `BizId/CallId`、受理不等于送达、供应商固定错误映射、超时转 UNKNOWN、模板参数上限、号码脱敏和凭据不出日志。
2. 验证 RED。
3. 使用官方 Java SDK 和 RAM 最小权限配置实现适配器，不硬编码 AccessKey。
4. 验证 GREEN；测试不得访问收费 API。
5. Commit: `feat(notification): 接入阿里云短信与语音`。

### Task 13: 实现通知与升级工作台

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V55__publish_notification_navigation.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Create: `mine-web/src/modules/notification/api/notification.ts`
- Create: `mine-web/src/modules/notification/pages/NotificationCenterPage.vue`
- Create: `mine-web/src/modules/notification/pages/NotificationCenterPage.test.ts`
- Create: `mine-web/src/modules/notification/components/NotificationRuntimeOverview.vue`
- Create: `mine-web/src/modules/notification/components/NotificationChannelPanel.vue`
- Create: `mine-web/src/modules/notification/components/NotificationPolicyPanel.vue`
- Create: `mine-web/src/modules/notification/components/NotificationDeliveryPanel.vue`
- Create: `mine-web/src/modules/notification/components/NotificationTicketPanel.vue`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Steps:**

1. 先写迁移和 Vue 失败测试，覆盖超级管理员菜单、运行结论、渠道、策略、送达、事件单、权限隐藏、确认、加载/空/错态、中英文和窄屏。
2. 验证 RED。
3. 使用 Naive UI 和 UnoCSS 实现暖橙矿石灰工业控制台，不添加页面 `<style>`、内联 CSS、硬编码颜色和可见文案。
4. Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint && pnpm --dir mine-web test:unit && pnpm --dir mine-web build`。
5. Commit: `feat(frontend): 交付通知与升级工作台`。

### Task 14: 部署、可观测性、文档与验收收口

**Files:**
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/env/standalone.env.example`
- Modify: `deploy/config/prometheus/rules/platform.yml`
- Modify: `deploy/tests/compose-config.sh`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/NotificationMetrics.java`
- Modify: `docs/04-device-model-strategy.md`
- Modify: `docs/05-messaging-telemetry-data.md`
- Modify: `docs/07-frontend.md`
- Modify: `docs/08-security-auth-audit.md`
- Modify: `docs/09-deployment-operations.md`
- Modify: `docs/10-testing-quality-gates.md`
- Modify: `docs/11-contracts-and-database.md`
- Modify: `docs/12-roadmap-and-codex-workflow.md`
- Create: `docs/runbooks/notification-runtime.md`
- Create: `docs/reports/phase-2-notification-acceptance.md`
- Modify: `docs/README.md`

**Steps:**

1. 先写部署和 Prometheus 失败测试，要求独立密钥、默认禁用收费适配器、低基数指标、积压/UNKNOWN/死信/SLA 告警。
2. 验证 RED。
3. 更新 Compose、secret、环境变量、运行手册和验收报告。
4. 运行定向 Maven、前端全门禁、部署测试和 `bash scripts/verify.sh`。
5. 使用 `requesting-code-review` 技能复核安全、幂等、数据库和前端治理，修复后重跑受影响测试。
6. 路线图只把非现场自动化证据覆盖的通知能力标记通过，真实资质、号码、送达、值班响应、容量、HA、备份恢复和长稳保持 `DEFERRED`。
7. Commit: `docs(notification): 完成通知运行验收与运维说明`。
