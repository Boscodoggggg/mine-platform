# Built-in Notification Providers Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将通知契约收回 `mine-server`，提供数据库驱动的官方 Provider 配置管理，并实现七种平台内置通知 Provider。

**Architecture:** Provider 由编译期 Spring Bean 注册表封闭管理，渠道的非敏感配置与 AES-256-GCM 加密 secrets 分库存储。外部调用通过可替换传输边界测试，SMTP 使用 Jakarta Mail，阿里云使用官方 SDK，所有供应商成功受理统一返回 `SUBMITTED`。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis、PostgreSQL、AES-256-GCM、Jakarta Mail、Apache HttpClient 5、Alibaba Cloud Java SDK、JUnit 5、Mockito、Testcontainers。

---

### Task 1: 合并通知适配器契约

**Files:**
- Modify: `pom.xml`
- Delete: `mine-notification-sdk/`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/adapter/*.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/adapter/NotificationAdapterContractTest.java`

**Steps:**

1. 运行现有 `NotificationAdapterContractTest`，确认重构基线通过。
2. 将纯 Java 契约移动到 `mine-server` 内部包，并给请求增加递归冻结的 `credentials` 快照。
3. 从根 Reactor 删除独立模块，删除旧目录。
4. 运行新包契约测试和 `mine-server` 编译。
5. Commit: `refactor(notification): 合并内部通知适配器契约`。

### Task 2: 建立封闭 Provider 注册表与数据库配置 API

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/provider/NotificationProviderDefinition.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/provider/NotificationProviderRegistry.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/model/NotificationChannel.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/repository/NotificationChannelMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationChannelRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/dto/NotificationChannelResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/service/NotificationChannelService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/controller/NotificationChannelController.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/provider/NotificationProviderRegistryTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/service/NotificationChannelServiceTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/controller/NotificationChannelControllerTest.java`

**Steps:**

1. 先写失败测试：未知 Provider、渠道类型不匹配、重复键、禁用草稿、启用必填校验、密文不出响应、版本更新重新加密和乐观锁冲突。
2. 运行定向测试并确认 RED。
3. 实现编译期 Provider 定义与注册表；API 只返回受控字段元数据。
4. 实现注解 Mapper、服务、精确 RBAC、AES 密文读写和安全审计摘要。
5. 运行定向测试与数据库迁移测试并确认 GREEN。
6. Commit: `feat(notification): 管理数据库通知渠道配置`。

### Task 3: 实现平台内置 Provider

**Files:**
- Modify: `mine-server/pom.xml`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/provider/http/*.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/provider/smtp/*.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/provider/im/*.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/notification/provider/aliyun/*.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/notification/provider/**/*Test.java`

**Steps:**

1. 先写失败测试：七种固定 Provider、HTTP 正文与签名、HTTPS/主机/地址阻断、SMTP 参数、阿里云请求映射、受理标识和错误分类。
2. 运行定向测试并确认 RED。
3. 实现固定 DNS 解析结果、TLS 原始主机名校验的 Apache HttpClient 5 传输和 SSRF 防护；实现企业微信、钉钉、飞书与通用 Webhook。
4. 实现 Jakarta Mail SMTP Provider。
5. 使用阿里云官方 SDK 实现短信和语音客户端；成功分别保存 `BizId` 和 `CallId`。
6. 运行 Provider、渠道服务、数据库迁移和 `mine-server` 全量单元测试。
7. Commit: `feat(notification): 实现内置通知供应商`。

### Task 4: 更新接口契约与部署配置

**Files:**
- Modify: `docs/openapi/mine-api.yaml`
- Modify: `deploy/compose/.env.example`
- Modify: `deploy/compose/docker-compose.yml`
- Modify: notification runbook and roadmap documents

**Steps:**

1. 先写 OpenAPI/部署契约检查。
2. 记录 Provider 字段、密钥挂载和真实验证延期项。
3. 运行 OpenAPI、Compose 和文档检查。
4. Commit: `docs(notification): 发布内置供应商配置契约`。
