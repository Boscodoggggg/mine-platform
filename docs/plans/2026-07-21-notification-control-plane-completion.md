# Notification Control Plane Completion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在现有内置 Provider 和通知渠道工作台基础上，补齐可上线维护的接收对象、通知模板和不可变升级策略控制面，为后续可靠投递、送达状态和事件单闭环提供可发布配置。

**Architecture:** 所有能力继续内聚在 `mine-server` 的通知领域，不新增开放 Maven SDK。渠道、接收对象、模板和策略分别拥有独立 API、权限和审计；接收目标使用 AES-256-GCM 密文与密钥化盲索引保存，普通查询只返回脱敏值，明文只允许通过禁止缓存的独立权限接口读取。策略使用草稿版本编辑、发布快照不可变的模型引用已启用依赖。

**Tech Stack:** Java 21、Spring Boot 4、PostgreSQL、MyBatis、AES-256-GCM、HMAC-SHA-256、Sa-Token、OpenAPI、Vue 3、Naive UI、UnoCSS、Vue I18n、JUnit 5、Mockito、Vitest。

---

### Task 1: 为接收目标建立密钥化盲索引

**Files:**
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/security/NotificationSecretCipher.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/security/AesGcmNotificationSecretCipher.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/security/AesGcmNotificationSecretCipherTest.java`

**Steps:**

1. 先增加失败测试，覆盖相同标准化目标产生稳定摘要、不同密钥或用途产生不同摘要、摘要不包含目标明文、空值和超长值被拒绝。
2. 运行 `./mvnw -B -ntp -pl mine-server -am -Dtest=AesGcmNotificationSecretCipherTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认 RED。
3. 使用通知独立密钥和带用途域分离的 HMAC-SHA-256 生成 `sha256:` 前缀盲索引，保持现有数据库约束兼容。
4. 重跑定向测试，确认 GREEN。
5. 提交 `feat(notification): 加固接收目标密钥化摘要`。

### Task 2: 实现接收组与接收目标管理 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/model/NotificationRecipientGroup.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/model/NotificationRecipient.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationRecipientGroupRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationRecipientGroupResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationRecipientRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationRecipientResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationRecipientTargetResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/repository/NotificationRecipientMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationRecipientService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationRecipientController.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationRecipientServiceTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationRecipientControllerTest.java`

**Steps:**

1. 先写服务与控制器失败测试，覆盖组编码唯一、乐观锁、系统用户/外部联系人规则、渠道目标校验、标准化去重、密文不出普通 API、独立敏感权限、`no-store`、审计和禁用状态。
2. 验证 RED。
3. 用注解 Mapper 实现单表 CRUD；创建与更新时保存密文、盲索引和脱敏值，真实目标读取写独立审计。
4. 验证 GREEN。
5. 提交 `feat(notification): 管理通知接收组与接收目标`。

### Task 3: 实现安全通知模板管理 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/model/NotificationTemplate.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationTemplateRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationTemplateResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/repository/NotificationTemplateMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationTemplateService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationTemplateController.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationTemplateServiceTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationTemplateControllerTest.java`

**Steps:**

1. 先写失败测试，覆盖模板编码唯一、渠道/事件/语言枚举、变量白名单、未知变量、危险表达式、标题/正文上限、Provider 模板编码、乐观锁和审计。
2. 验证 RED。
3. 实现只允许 `{{variable}}` 占位符的安全校验和单表 CRUD，不在控制面执行任意表达式。
4. 验证 GREEN。
5. 提交 `feat(notification): 管理安全通知模板`。

### Task 4: 实现不可变升级策略发布 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/model/NotificationPolicy.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/model/NotificationPolicyVersion.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/model/NotificationPolicyStep.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationPolicyRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationPolicyResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/repository/NotificationPolicyMapper.java`
- Create: `mine-server/src/main/resources/mapper/notification/NotificationPolicyMapper.xml`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationPolicyService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationPolicyController.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationPolicyServiceTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationPolicyControllerTest.java`

**Steps:**

1. 先写失败测试，覆盖草稿创建、步骤顺序与延迟、启用依赖、等级/矿区/规则匹配条件、发布幂等、发布快照不可修改、新草稿派生、归档和重叠优先级冲突。
2. 验证 RED。
3. 简单写操作用注解，多表依赖和冲突查询用 XML；发布事务中锁定根策略和草稿并记录发布人、发布时间。
4. 验证 GREEN，并运行数据库集成测试。
5. 提交 `feat(notification): 发布版本化告警升级策略`。

### Task 5: 发布权限、OpenAPI 与前端类型

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V60__publish_notification_control_permissions.sql`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`
- Modify: `docs/openapi/mine-api.yaml`
- Modify: generated frontend API types according to the repository generator output

**Steps:**

1. 先扩展迁移测试，校验接收对象、敏感目标、模板和策略权限节点的父子关系及管理员授权。
2. 验证 RED。
3. 发布 `notification:recipient:view/edit/secret:view`、`notification:template:view/edit`、`notification:policy:view/edit/publish` 权限，补齐 OpenAPI 并生成前端类型。
4. 验证迁移、OpenAPI 和前端类型检查均通过。
5. 提交 `feat(notification): 发布通知控制面权限与契约`。

### Task 6: 将接收对象、模板和策略接入通知工作台

**Files:**
- Modify: `mine-web/src/modules/notification/api/notification.ts`
- Modify: `mine-web/src/modules/notification/pages/NotificationChannelManagementPage.vue`
- Create: `mine-web/src/modules/notification/components/NotificationRecipientsPanel.vue`
- Create: `mine-web/src/modules/notification/components/NotificationTemplatesPanel.vue`
- Create: `mine-web/src/modules/notification/components/NotificationPoliciesPanel.vue`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`
- Add or modify matching Vitest files

**Steps:**

1. 先写页面与 API 失败测试，覆盖权限可见性、空态、增改、敏感目标查看、模板变量提示、策略草稿/发布确认和错误恢复。
2. 验证 RED。
3. 在现有“告警管理 → 通知渠道”工作台中增加接收对象、模板和升级策略页签，不新增语义重复菜单。
4. 运行 Vitest、`check:ui-governance`、typecheck、lint 和 build。
5. 提交 `feat(frontend): 完善通知与升级配置工作台`。

### Task 7: 完成回归与上线边界文档

**Files:**
- Modify: `README.md`
- Modify: `docs/roadmap.md`
- Modify: relevant notification deployment and API documentation

**Steps:**

1. 运行后端全量测试、数据库迁移测试和前端全量质量门禁。
2. 使用管理员账号验证菜单、权限、接收对象、模板、草稿发布和敏感目标审计闭环；不调用收费短信、电话、邮件或企业 IM 外部服务。
3. 记录密钥挂载、权限拆分、备份恢复、发布回滚和真实供应商联调仍需部署方完成的边界。
4. 提交 `docs(notification): 补充通知控制面上线说明`。
