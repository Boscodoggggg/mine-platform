# S3 Object Storage Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 `mine-server` 内建立标准 S3 对象存储基础设施，使用 RustFS 支撑开发环境和场景资源集成测试，并将三维资源读取从本地 `Path` 解耦。

**Architecture:** 通用 `ObjectStorage` 端口和 AWS SDK v2 S3 实现位于 `mine-server` 基础设施包，由 Spring 条件装配选择。场景模块保留 staging、安全校验、内容寻址和清单职责，通过通用端口保存与读取对象；RustFS 只存在于 Compose 和测试容器配置中。

**Tech Stack:** Java 21、Spring Boot 4、AWS SDK for Java 2.x、JUnit 5、AssertJ、Testcontainers、RustFS、Docker Compose。

---

### Task 1：定义对象存储契约和配置边界

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/infrastructure/storage/ObjectStorage.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/infrastructure/storage/ObjectStorageProperties.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/infrastructure/storage/ObjectStorageException.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/infrastructure/storage/package-info.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/infrastructure/storage/ObjectStoragePropertiesTest.java`

**Step 1: Write the failing test**

测试有效 S3 配置可规范化，空 bucket、非 HTTP(S) endpoint、空 region、缺失凭据和非法 staging 目录配置在构造阶段失败；所有公开 Java 类型和方法使用中文 Javadoc。

**Step 2: Run test to verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=ObjectStoragePropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，通用对象存储属性类型不存在。

**Step 3: Write minimal implementation**

定义 `put`、`head`、`get` 和 `delete` 所需的不可变请求/响应 record；范围使用闭区间并在构造阶段校验。配置只表达标准 S3 信息，不出现 RustFS 类型。

**Step 4: Run test to verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=ObjectStoragePropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

### Task 2：实现标准 S3 适配器和条件装配

**Files:**
- Modify: `pom.xml`
- Modify: `mine-server/pom.xml`
- Create: `mine-server/src/main/java/com/bosco/mine/server/infrastructure/storage/S3ObjectStorage.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/infrastructure/storage/ObjectStorageConfiguration.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/infrastructure/storage/S3ObjectStorageTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/infrastructure/storage/ObjectStorageConfigurationTest.java`

**Step 1: Write failing adapter tests**

使用 AWS SDK 提供的可替换 HTTP client 或本地协议测试服务验证对象键、bucket、Range Header、响应元数据、流关闭和 SDK 异常转换；配置测试断言 `type=s3` 时只产生一个 `ObjectStorage` Bean。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=S3ObjectStorageTest,ObjectStorageConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，S3 实现和 AWS SDK 依赖不存在。

**Step 3: Implement minimal adapter**

在父 POM 管理 AWS SDK BOM，在 `mine-server` 引入 `s3` 和必要 HTTP client；创建 path-style S3Client，所有请求只使用标准 S3 API，并将服务端细节封装为中文存储异常。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=S3ObjectStorageTest,ObjectStorageConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

### Task 3：将场景资源适配到通用对象存储

**Files:**
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/SceneAssetStorage.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/FileSystemSceneAssetStorage.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/ObjectSceneAssetStorage.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneAssetService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneAssetController.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/storage/FileSystemSceneAssetStorageTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/storage/ObjectSceneAssetStorageTest.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/SceneAssetControllerTest.java`

**Step 1: Write failing scene storage tests**

先定义不暴露 `Path` 的读取契约，覆盖完整读取、单范围、缺失对象、同摘要幂等写入、3D Tiles 多文件对象键和失败时 staging 清理。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=ObjectSceneAssetStorageTest,SceneAssetControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，场景接口仍返回本地 Path。

**Step 3: Implement minimal scene adapter**

复用现有 `SceneAssetPackageValidator` 在 staging 目录生成可信 payload 和清单，然后上传 `scene/{digest}/{relativePath}`；Controller 按读取结果构造 200/206 响应并负责关闭流。文件系统实现同步适配新契约，仅供快速测试使用。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=FileSystemSceneAssetStorageTest,ObjectSceneAssetStorageTest,SceneAssetControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

### Task 4：接入 RustFS 开发 Compose 和真实契约测试

**Files:**
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/env/standalone.env.example`
- Modify: `deploy/tests/compose-config.sh`
- Modify: `mine-server/src/main/resources/application.yml`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/RustFsObjectStorageIT.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/SceneAssetManagementIT.java`
- Modify: `deploy/README.md`

**Step 1: Write failing RustFS integration test**

Testcontainers 启动固定镜像，测试前通过标准 S3 API 创建 bucket，验证 put、head、完整 get、Range get、覆盖写和 delete；场景管理集成测试改为真实 S3 存储。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=RustFsObjectStorageIT,SceneAssetManagementIT -Dsurefire.failIfNoSpecifiedTests=false verify`

Expected: FAIL，RustFS 测试容器和部署服务尚未接入。

**Step 3: Implement Compose and test configuration**

增加 RustFS 服务、命名数据卷、健康检查、最小权限开发凭据和 bucket 初始化；`mine-server` 使用标准 S3 环境变量。镜像必须固定版本，测试不得使用 `latest`。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=RustFsObjectStorageIT,SceneAssetManagementIT -Dsurefire.failIfNoSpecifiedTests=false verify`

Run: `bash deploy/tests/compose-config.sh`

Expected: PASS，Compose 展开配置包含健康的 RustFS 依赖且无明文生产密钥。

### Task 5：执行回归和文档收口

**Files:**
- Modify: `docs/01-system-architecture.md`
- Modify: `docs/09-deployment-operations.md`
- Modify: `docs/13-technology-and-licenses.md`
- Modify: `docs/14-decisions-and-glossary.md`

**Step 1: Update documentation assertions**

记录 Java 侧只使用标准 S3、RustFS 仅为开发测试实现、生产供应商待部署评审，并删除“当前 Compose 不包含对象存储”等过期陈述。

**Step 2: Run focused and full verification**

Run: `./mvnw -pl mine-server -am test`

Run: `./mvnw -pl mine-server -am verify`

Run: `bash deploy/tests/compose-config.sh`

Expected: 全部 PASS；若 Docker 不可用，明确记录被跳过的 RustFS 集成测试及其他已通过验证。
