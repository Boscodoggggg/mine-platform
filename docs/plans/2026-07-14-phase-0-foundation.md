# 阶段 0 工程底座 Implementation Plan

> **For Codex:** REQUIRED SKILL: Use executing-plans to implement this plan task-by-task.

**Goal:** 建立可构建、可登录、可鉴权、可观测并能通过单机 Docker Compose 启动的 mine-platform 空平台。

**Architecture:** 使用 Maven 多模块管理 Java 服务和共享模块；mine-server 先实现 Sa-Token 与五表 RBAC；RabbitMQ、PostgreSQL/TimescaleDB、Redis 通过 Compose 提供。前端从 SoybeanAdmin Naive UI 基线裁剪，只保留布局、登录和动态路由。

**Tech Stack:** Java 21、Spring Boot 4.1、Sa-Token 1.45、MyBatis-Plus Boot 4、Flyway、RabbitMQ 4.1、PostgreSQL 18、TimescaleDB OSS、Redis 8、Vue 3、Vite 8、Naive UI、Vitest、Playwright、Docker Compose。

---

## 前置确认

执行 Task 1 前必须确认：

- Maven groupId 已固定为 `com.bosco`，Java 基础包已固定为 `com.bosco.mine`，不得再使用临时包名。
- 项目许可证已经固定为 Apache-2.0，根 LICENSE 和 NOTICE 必须进入所有源码及发行包。
- SoybeanAdmin 基线 commit。
- 构建环境是 amd64 还是还需要 arm64。

### Task 1：初始化仓库治理文件

**Files:**

- Create: pom.xml
- Create: .gitignore
- Create: .editorconfig
- Create: .mvn/wrapper/maven-wrapper.properties
- Create: mvnw
- Create: mvnw.cmd
- Create: README.md
- Use existing: AGENTS.md
- Create: docs/README.md

**Step 1: 创建父 POM 的结构测试**

Create: build-tests/verify-root.sh

脚本检查 pom.xml 包含 packaging=pom 和计划模块名；当前应失败，因为 pom.xml 尚不存在。

**Step 2: 运行并确认失败**

Run:

~~~bash
bash build-tests/verify-root.sh
~~~

Expected: FAIL，提示 pom.xml 不存在。

**Step 3: 创建最小父工程**

父 POM 声明 Java 21、Spring Boot 4.1.0、UTF-8，并先注册：

~~~text
mine-common
mine-device-model
mine-messaging
mine-protocol-sdk
mine-protocol-engine
mine-gateway
mine-telemetry
mine-server
~~~

使用 Maven Wrapper；禁止依赖开发机全局 Maven 版本。

**Step 4: 运行结构和 Maven 验证**

Run:

~~~bash
bash build-tests/verify-root.sh
./mvnw -N help:effective-pom
~~~

Expected: PASS。

**Step 5: 提交**

~~~bash
git add .
git commit -m "chore(build): 初始化Maven多模块工程"
~~~

### Task 2：创建共享模块空骨架和依赖边界

**Files:**

- Create: mine-common/pom.xml
- Create: mine-device-model/pom.xml
- Create: mine-messaging/pom.xml
- Create: mine-protocol-sdk/pom.xml
- Create: mine-protocol-engine/pom.xml
- Create: mine-gateway/pom.xml
- Create: mine-telemetry/pom.xml
- Create: mine-server/pom.xml
- Create: mine-server/src/test/java/com/bosco/mine/architecture/ModuleDependencyTest.java

**Step 1: 写失败的 ArchUnit 测试**

测试要求 common/device-model/messaging 不依赖 server、gateway、telemetry 包；当前因模块和包不存在而失败。

**Step 2: 运行失败测试**

~~~bash
./mvnw -pl mine-server -am test -Dtest=ModuleDependencyTest
~~~

Expected: FAIL，缺少被测类/模块。

**Step 3: 创建最小模块和 package-info**

每个 public package 写中文 Javadoc。不要创建无行为的 BaseService/BaseEntity。

**Step 4: 运行**

~~~bash
./mvnw test
~~~

Expected: 所有模块编译，架构测试 PASS。

**Step 5: 提交**

~~~bash
git commit -am "chore(backend): 建立后端模块边界"
~~~

### Task 3：实现公共错误、时钟和 ID

**Files:**

- Create: mine-common/src/main/java/com/bosco/mine/common/error/ErrorCode.java
- Create: mine-common/src/main/java/com/bosco/mine/common/error/MineException.java
- Create: mine-common/src/main/java/com/bosco/mine/common/time/MineClock.java
- Create: mine-common/src/main/java/com/bosco/mine/common/id/IdGenerator.java
- Test: mine-common/src/test/java/com/bosco/mine/common/error/MineExceptionTest.java
- Test: mine-common/src/test/java/com/bosco/mine/common/time/MineClockTest.java

**Step 1: 写失败测试**

断言异常保留稳定 code/details；测试时钟可固定；ID 满足 UUIDv7 形式和时间有序性。

**Step 2: 运行**

~~~bash
./mvnw -pl mine-common test
~~~

Expected: FAIL，目标类型不存在。

**Step 3: 最小实现**

所有 public 类型和方法添加中文 Javadoc。IdGenerator 不自行发明加密随机算法，使用经过验证实现或 PostgreSQL 18 uuidv7 方案；最终选型写 ADR。

**Step 4: 运行**

~~~bash
./mvnw -pl mine-common test
~~~

Expected: PASS。

**Step 5: 提交**

~~~bash
git commit -am "feat(common): 新增统一错误时间与标识能力"
~~~

### Task 4：定义统一消息信封 v1

**Files:**

- Create: mine-messaging/src/main/java/com/bosco/mine/messaging/MessageEnvelope.java
- Create: mine-messaging/src/main/java/com/bosco/mine/messaging/MessageType.java
- Create: mine-device-model/src/main/java/com/bosco/mine/model/message/UnifiedDeviceMessage.java
- Create: mine-device-model/src/main/java/com/bosco/mine/model/message/TelemetryPayload.java
- Test: mine-messaging/src/test/java/com/bosco/mine/messaging/MessageEnvelopeJsonTest.java
- Test: mine-device-model/src/test/java/com/bosco/mine/model/message/UnifiedDeviceMessageValidationTest.java
- Create: docs/contracts/message-envelope-v1.json

**Step 1: 写 JSON 黄金测试**

使用固定 JSON fixture 断言字段名、时间、schemaVersion 和未知可选字段兼容。

**Step 2: 确认失败**

~~~bash
./mvnw -pl mine-messaging,mine-device-model -am test
~~~

Expected: FAIL，信封类型不存在。

**Step 3: 实现不可变 record/类型**

禁止消息对象依赖 MyBatis Record 或 Spring AMQP Message。

**Step 4: 测试**

Expected: JSON 往返一致，必填字段缺失被拒绝。

**Step 5: 提交**

~~~bash
git commit -am "feat(messaging): 定义统一设备消息信封"
~~~

### Task 5：启动 mine-server 和统一错误响应

**Files:**

- Create: mine-server/src/main/java/com/bosco/mine/server/MineServerApplication.java
- Create: mine-server/src/main/java/com/bosco/mine/server/bootstrap/web/GlobalExceptionHandler.java
- Create: mine-server/src/main/java/com/bosco/mine/server/bootstrap/web/TraceIdFilter.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/SystemStatusController.java
- Test: mine-server/src/test/java/com/bosco/mine/server/bootstrap/web/ProblemDetailTest.java
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/SystemStatusControllerTest.java

**Step 1: 写 MockMvc 失败测试**

断言 /api/v1/system/status 返回版本；测试异常包含 code、message、traceId、timestamp。

**Step 2: 运行**

~~~bash
./mvnw -pl mine-server -am test
~~~

Expected: FAIL，应用和控制器不存在。

**Step 3: 最小实现**

只创建 status 和错误层，不提前生成全部 CRUD。

**Step 4: 运行**

Expected: PASS。

**Step 5: 提交**

~~~bash
git commit -am "feat(server): 新增服务启动与统一错误响应"
~~~

### Task 6：创建业务数据库与 Flyway

**Files:**

- Create: mine-server/src/main/resources/db/migration/V1__create_system_rbac.sql
- Create: mine-server/src/main/resources/db/migration/V2__create_audit_and_outbox.sql
- Create: mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java
- Create: mine-server/src/test/resources/application-test.yml

**Step 1: 写 Testcontainers 失败测试**

启动 PostgreSQL 18，运行应用 migration，断言五表 RBAC、审计、outbox/inbox 存在且唯一约束正确。

**Step 2: 运行**

~~~bash
./mvnw -pl mine-server -am verify -Dtest=DatabaseMigrationIT
~~~

Expected: FAIL，migration 不存在。

**Step 3: 写 migration**

创建 sys_user、sys_role、sys_menu、sys_user_role、sys_role_menu、sys_login_log、sys_operation_log、sys_outbox_event、sys_inbox_event。不要插入普通预置角色。

**Step 4: 运行空库和重复启动测试**

Expected: PASS，第二次启动无 schema 变化。

**Step 5: 提交**

~~~bash
git commit -am "feat(database): 创建系统权限审计与消息表"
~~~

### Task 7：实现 Sa-Token 登录和五表 RBAC

**Files:**

- Create: mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/AuthController.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/system/service/AuthService.java
- Create: mine-server/src/main/java/com/bosco/mine/server/bootstrap/security/StpInterfaceImpl.java
- Create: mine-server/src/main/java/com/bosco/mine/server/bootstrap/security/SaTokenConfiguration.java
- Create: mine-server/src/main/java/com/bosco/mine/server/modules/system/repository/SystemAuthMapper.java
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/AuthControllerTest.java
- Test: mine-server/src/test/java/com/bosco/mine/server/bootstrap/security/PermissionIntegrationIT.java

**Step 1: 写失败测试**

覆盖错误密码、成功登录、无 token 401、缺权限 403、有权限 200、踢下线后失效。

**Step 2: 运行**

Expected: FAIL，登录 API 不存在。

**Step 3: 实现**

通过 StpInterface 读取角色和 sys_menu.permission。仅初始化超级管理员；初始密码来自 secret，首次登录强制修改。

**Step 4: 运行**

~~~bash
./mvnw -pl mine-server -am verify
~~~

Expected: PASS。

**Step 5: 提交**

~~~bash
git commit -am "feat(auth): 实现SaToken登录与五表权限"
~~~

### Task 8：初始化 RabbitMQ 双 vhost 和内部拓扑

**Files:**

- Create: deploy/config/rabbitmq/enabled_plugins
- Create: deploy/config/rabbitmq/definitions.json
- Create: deploy/config/rabbitmq/rabbitmq.conf
- Create: mine-messaging/src/main/java/com/bosco/mine/messaging/topology/MineTopology.java
- Test: mine-messaging/src/test/java/com/bosco/mine/messaging/RabbitTopologyIT.java

**Step 1: 写 RabbitMQ Testcontainers 失败测试**

断言 MQTT 插件启用、两个 vhost 存在、内部 exchange/queue/binding 正确、设备账号不能访问平台 vhost。

**Step 2: 运行**

Expected: FAIL，definitions 和拓扑不存在。

**Step 3: 最小定义**

创建 /mine-device、/mine-platform、mine.telemetry.x、mine.command.x、mine.config.x、mine.dlx 和基础队列。

**Step 4: 运行**

Expected: PASS。

**Step 5: 提交**

~~~bash
git commit -am "feat(messaging): 配置RabbitMQ双虚拟主机"
~~~

### Task 9：创建 gateway 和 telemetry 可运行骨架

**Files:**

- Create: mine-gateway/src/main/java/com/bosco/mine/gateway/MineGatewayApplication.java
- Create: mine-telemetry/src/main/java/com/bosco/mine/telemetry/MineTelemetryApplication.java
- Create: 各模块 application.yml
- Test: 各模块 ApplicationContextTest.java

**Step 1: 写上下文失败测试**

断言应用在测试配置启动、暴露 health/info/metrics。

**Step 2: 运行**

Expected: FAIL。

**Step 3: 实现最小启动类和 Actuator**

不在本任务实现业务消费者。

**Step 4: 运行**

~~~bash
./mvnw -pl mine-gateway,mine-telemetry -am test
~~~

Expected: PASS。

**Step 5: 提交**

~~~bash
git commit -am "feat(runtime): 新增接入与遥测服务骨架"
~~~

### Task 10：建立 mine-web

**Files:**

- Create: mine-web/（从锁定 SoybeanAdmin commit 裁剪）
- Preserve: mine-web/LICENSE
- Create: mine-web/THIRD-PARTY-NOTICES.md
- Modify: mine-web/package.json
- Create: mine-web/src/modules/system/pages/login/
- Test: mine-web/src/modules/system/pages/login/LoginPage.test.ts

**Step 1: 记录上游**

在 THIRD-PARTY-NOTICES 写仓库 URL、commit、MIT 和保留/删除内容。

**Step 2: 写失败组件测试**

断言用户名密码必填、错误展示 traceId、成功保存会话并跳转。

**Step 3: 运行**

~~~bash
pnpm --dir mine-web test
~~~

Expected: FAIL。

**Step 4: 裁剪与实现**

保留 Naive UI、布局、主题、路由和请求基础；删除 Mock 业务页面。接入 /api/v1/auth。

**Step 5: 验证**

~~~bash
pnpm --dir mine-web typecheck
pnpm --dir mine-web lint
pnpm --dir mine-web test
pnpm --dir mine-web build
~~~

Expected: PASS。

**Step 6: 提交**

~~~bash
git commit -am "feat(frontend): 初始化Vue3管理端与登录页"
~~~

### Task 11：动态菜单与按钮权限

**Files:**

- Create: mine-web/src/app/router/dynamic-routes.ts
- Create: mine-web/src/app/permission/permission.ts
- Create: mine-web/src/modules/system/api/auth.ts
- Test: mine-web/src/app/router/dynamic-routes.test.ts
- Test: mine-web/src/app/permission/permission.test.ts
- Test: mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/MenuControllerTest.java

**Step 1: 写失败测试**

覆盖 DIRECTORY/MENU/BUTTON、componentKey 白名单、未知 componentKey 降级和按钮权限。

**Step 2: 实现后端菜单 API 和前端映射**

禁止后端返回任意文件路径。

**Step 3: 运行前后端测试**

Expected: PASS。

**Step 4: 提交**

~~~bash
git commit -am "feat(auth): 实现动态菜单与按钮权限"
~~~

### Task 12：单机 Compose

**Files:**

- Create: deploy/compose/compose-standalone.yml
- Create: deploy/env/standalone.env.example
- Create: deploy/scripts/preflight.sh
- Create: deploy/scripts/health-check.sh
- Create: deploy/config/nginx/nginx.conf
- Test: deploy/tests/standalone-smoke.sh

**Step 1: 写失败 smoke**

脚本检查所有容器 healthy、HTTPS/API、RabbitMQ MQTT、PostgreSQL、Timescale 扩展和 Redis。

**Step 2: 运行**

~~~bash
bash deploy/tests/standalone-smoke.sh
~~~

Expected: FAIL，Compose 不存在。

**Step 3: 创建 Compose**

使用固定镜像 tag/digest；secret 通过文件；数据卷明确；所有服务 healthcheck、资源和日志限制。

**Step 4: 启动和验证**

~~~bash
docker compose -f deploy/compose/compose-standalone.yml up -d
bash deploy/tests/standalone-smoke.sh
~~~

Expected: PASS。

**Step 5: 提交**

~~~bash
git commit -am "feat(deploy): 新增单机一体化部署"
~~~

### Task 13：指标、日志和基础仪表板

**Files:**

- Create: deploy/config/prometheus/prometheus.yml
- Create: deploy/config/prometheus/rules/platform.yml
- Create: deploy/config/grafana/provisioning/
- Create: deploy/config/grafana/dashboards/platform-overview.json
- Create: deploy/config/alloy/config.alloy
- Test: deploy/tests/observability-smoke.sh

**Step 1: 写失败测试**

检查三个 Java 服务、RabbitMQ 和数据库 targets 为 up，日志可按 traceId 查询。

**Step 2: 实现和运行**

Expected: 告警规则可加载、Grafana dashboard 自动创建、日志无 secret。

**Step 3: 提交**

~~~bash
git commit -am "feat(observability): 新增基础指标日志与仪表板"
~~~

### Task 14：CI 和质量门禁

**Files:**

- Create: .github/workflows/ci.yml
- Create: .github/dependabot.yml 或所选依赖更新配置
- Create: scripts/verify.sh
- Create: scripts/generate-sbom.sh
- Modify: README.md

**Step 1: 写 verify 脚本**

顺序执行 Maven test/verify、前端 typecheck/lint/test/build、Compose config、许可证/SBOM。

**Step 2: 本地运行**

~~~bash
bash scripts/verify.sh
~~~

Expected: PASS。

**Step 3: 配置 CI**

CI 缓存不能成为构建必需；失败输出测试报告和日志。

**Step 4: 提交**

~~~bash
git commit -am "chore(ci): 建立全仓质量门禁"
~~~

## 阶段 0 最终验收

Run:

~~~bash
bash scripts/verify.sh
docker compose -f deploy/compose/compose-standalone.yml up -d
bash deploy/tests/standalone-smoke.sh
bash deploy/tests/observability-smoke.sh
~~~

手工验收：

- 超级管理员首次登录和修改密码。
- 创建角色、菜单/按钮授权并重新登录生效。
- Grafana 查看服务和 RabbitMQ。
- 所有容器重启后数据库和配置仍存在。
- 关闭公网后重新启动不需要下载任何依赖或资源。

阶段报告保存：docs/reports/phase-0-acceptance.md。
