# Cesium 3D Scene and Independent 2D Fallback Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 交付可离线部署、可上传治理、可版本化发布、可叠加实时矿区事实，并在 WebGL 或模型失败时独立降级到二维视图的 Cesium 三维场景闭环。

**Architecture:** 管理 PostgreSQL 保存不可变三维资源清单、稳定场景、场景版本和有序图层；`SceneAssetStorage` 使用内容寻址隔离本地/共享文件系统并保留 S3 扩展边界。前端通过小型 `SceneViewport` 动态加载 Cesium 1.141，复用位置/空间/告警/运营 REST+WebSocket 数据，失败时切换到完全不依赖 Cesium 的 `MineCoordinateCanvas`。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis、PostgreSQL 18、Micrometer、Vue 3、TypeScript 6、Naive UI、UnoCSS、CesiumJS 1.141、ECharts 6、Vitest、Playwright、Docker Compose、Prometheus。

---

### Task 1：建立纯场景领域规则和资源格式策略

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/domain/SceneVersionPolicy.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/domain/SceneAssetPolicy.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/domain/package-info.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/domain/SceneVersionPolicyTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/domain/SceneAssetPolicyTest.java`

**Step 1: Write failing domain tests**

覆盖：

```java
assertThat(SceneAssetPolicy.typeFor("pit.glb", "model/gltf-binary"))
        .isEqualTo(SceneAssetType.GLB);
assertThatThrownBy(() -> SceneAssetPolicy.safeRelativePath("../secret"))
        .isInstanceOf(IllegalArgumentException.class);

SceneVersionPolicy.validateTransform(origin, rotation, scale);
assertThat(SceneVersionPolicy.contentHash(scene, normalizedLayers))
        .startsWith("sha256:");
```

测试固定层类型与资源类型匹配、图层编码/顺序唯一、有限坐标、缩放/透明度/距离上限、规范化顺序和摘要确定性。所有后端公开类型和方法必须写中文 Javadoc。

**Step 2: Run tests to verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=SceneVersionPolicyTest,SceneAssetPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，场景领域类型不存在。

**Step 3: Implement minimal pure policies**

使用不可变 record/enum；禁止访问 Spring、数据库、文件系统和网络。资源类型只允许 `GLB/TILES_3D`，图层类型只允许设计文档中的八类，未知值固定拒绝。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=SceneVersionPolicyTest,SceneAssetPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/domain mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/domain
git commit -m "feat(scene): 建立三维场景领域规则"
```

### Task 2：创建场景、版本、图层和资源数据库模型

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V50__create_scene_control_plane.sql`
- Create: `mine-server/src/test/java/com/bosco/mine/server/integration/SceneControlPlaneConstraintIT.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`

**Step 1: Write failing migration tests**

要求以下表存在且表及每个字段都有中文注释：

```text
mine_scene_asset
mine_scene_asset_file
mine_scene
mine_scene_version
mine_scene_layer
```

覆盖矿区作用域外键、资源摘要/大小/文件数/入口约束、文件安全相对路径和包内唯一、场景编码唯一、每矿区单一默认、版本修订唯一、单一草稿、单一发布版本、图层编码/顺序唯一、资源类型匹配、有限数值、发布版本不可更新/删除及跨矿引用拒绝。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=SceneControlPlaneConstraintIT,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，V50 表不存在。

**Step 3: Implement V50**

核心字段保持关系化；默认相机、二维视口和有界显示参数可以使用带大小/类型约束的 JSONB。使用部分唯一索引保护活动默认场景、每场景单一草稿和单一发布版本；触发器保护发布版本/图层和资源摘要不可变。使用 `COMMENT ON TABLE` 与 `COMMENT ON COLUMN` 覆盖全部字段。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=SceneControlPlaneConstraintIT,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，注释、约束、索引和升级迁移全部通过。

**Step 5: Commit**

```bash
git add mine-server/src/main/resources/db/migration/V50__create_scene_control_plane.sql mine-server/src/test/java/com/bosco/mine/server/integration/SceneControlPlaneConstraintIT.java mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java
git commit -m "feat(scene): 建立场景与三维资源控制面"
```

### Task 3：实现安全内容寻址资源存储

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/SceneAssetStorage.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/FileSystemSceneAssetStorage.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/SceneAssetStorageProperties.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/SceneAssetPackageValidator.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/package-info.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/storage/FileSystemSceneAssetStorageTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/storage/SceneAssetPackageValidatorTest.java`
- Modify: `mine-server/src/main/resources/application.yml`

**Step 1: Write failing storage and hostile package tests**

使用临时目录构造最小合法 GLB、最小 `tileset.json` ZIP，以及 Zip Slip、绝对路径、软链接、重复/大小写冲突路径、超文件数、超展开量、高压缩比、错误 GLB 长度、外部 URI 和不存在子资源样本。断言失败不产生最终内容目录。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=FileSystemSceneAssetStorageTest,SceneAssetPackageValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，存储接口和校验器不存在。

**Step 3: Implement storage and streaming validation**

上传先写 `root/.staging/{uuid}`，边读边计算 SHA-256 和大小；ZIP 使用有界流逐项解压，禁止跟随符号链接，最终原子移动到 `root/objects/{digest}`。属性显式配置根目录、上传/解压/单文件/文件数/压缩比上限和宽限期；非法配置启动失败。日志只能记录固定阶段、资源类型和摘要前缀，不记录用户路径或文件内容。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=FileSystemSceneAssetStorageTest,SceneAssetPackageValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/storage mine-server/src/main/resources/application.yml
git commit -m "feat(scene): 增加安全三维资源存储"
```

### Task 4：实现资源上传、清单和受权 Range 读取

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneMapper.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneAssetService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneAssetController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/dto/SceneAssetResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/dto/SceneAssetFileResponse.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/SceneAssetManagementIT.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/SceneAssetControllerTest.java`

**Step 1: Write failing integration/controller tests**

覆盖：上传 GLB/3D Tiles、同矿区内容摘要幂等、跨矿区隔离、超限返回 422、归档被发布版本引用的资源保持可读、未授权读取 403、未知/未列清单路径 404、`ETag/If-None-Match`、单范围 `Range` 返回 206、多个/越界范围拒绝、`nosniff` 和白名单媒体类型。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=SceneAssetManagementIT,SceneAssetControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，资源服务和接口不存在。

**Step 3: Implement resource service and controller**

数据库只保存校验完成的对象和文件清单；存储成功但事务失败留下的内容寻址对象由后续对账清理。文件读取必须先按 `assetId + siteId + relativePath` 查询清单，再读取返回的内部存储键，禁止 Controller 拼接宿主机路径。全部 Java 类型/方法写中文 Javadoc。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=SceneAssetManagementIT,SceneAssetControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene mine-server/src/test/java/com/bosco/mine/server/integration/SceneAssetManagementIT.java mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/SceneAssetControllerTest.java
git commit -m "feat(scene): 提供三维资源上传与安全读取"
```

### Task 5：实现场景草稿、图层和不可变发布

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneQueryService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneCommandService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneActionIdempotencyService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/dto/SceneRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/dto/SceneResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/dto/SceneVersionRequest.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/dto/SceneVersionResponse.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/dto/SceneLayerRequest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/SceneManagementIT.java`

**Step 1: Write failing lifecycle tests**

覆盖稳定场景 CRUD/归档、矿区唯一编码、单一默认、从发布版本创建下一草稿、乐观修改、批量图层替换、跨矿资产/坐标拒绝、发布摘要确定、上一发布变 `SUPERSEDED`、已发布版本不可修改、发布失败继续返回最后正确版本、幂等键同请求复用/异请求冲突。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=SceneManagementIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，场景服务不存在。

**Step 3: Implement transactional scene services**

所有入口先调用 `MineScopeAuthorizer.requireSite`。发布在单一事务锁定场景、草稿、图层和资产，调用纯策略规范化并计算摘要；发布/默认切换写审计和持久幂等响应。归档默认场景前必须先切换其他默认场景，发布版本和图层不允许原地回滚。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=SceneManagementIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene mine-server/src/test/java/com/bosco/mine/server/integration/SceneManagementIT.java
git commit -m "feat(scene): 实现场景版本与发布治理"
```

### Task 6：发布场景 REST、OpenAPI、菜单和精确权限

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneController.java`
- Create: `mine-server/src/main/resources/db/migration/V51__publish_scene_access.sql`
- Modify: `docs/openapi/mine-api.yaml`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/controller/MineManagementOpenApiTest.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/integration/DatabaseMigrationIT.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/SceneControllerTest.java`

**Step 1: Write failing API, permission and migration tests**

断言设计文档中的资源/场景/版本/图层/发布/默认接口全部进入 OpenAPI；后端注解分别使用 `mine:scene:view/edit/asset:upload/publish`；V51 在“矿区管理”下发布 `/mine/scene` 和组件键 `mine-scene-workbench`，BUTTON 不生成路由，授予菜单时只补齐必要只读依赖。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=SceneControllerTest,MineManagementOpenApiTest,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，接口和 V51 尚未发布。

**Step 3: Implement controllers, OpenAPI and V51**

上传使用 multipart 且有服务端大小上限；发布/默认/归档要求 `Idempotency-Key` 和乐观版本；列表分页最大 100，文件清单分页最大 500，禁止无界返回。OpenAPI 声明错误码、Range 响应和跨矿权限边界。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=SceneControllerTest,MineManagementOpenApiTest,DatabaseMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneController.java mine-server/src/main/resources/db/migration/V51__publish_scene_access.sql docs/openapi/mine-api.yaml mine-server/src/test
git commit -m "feat(scene): 发布场景接口与访问权限"
```

### Task 7：提供最后正确场景运行快照

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneRuntimeService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneRuntimeController.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/dto/SceneRuntimeResponse.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/integration/SceneRuntimeIT.java`
- Modify: `docs/openapi/mine-api.yaml`

**Step 1: Write failing runtime snapshot tests**

覆盖无默认场景、默认未发布、完整已发布快照、图层有序、受权同源资源 URL、schema 版本、内容摘要、局部坐标/WGS84 锚点、资源归档后历史快照仍可读、草稿错误不影响最后正确版本、跨矿区拒绝和固定降级原因。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=SceneRuntimeIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，运行快照不存在。

**Step 3: Implement runtime service**

一次查询读取默认场景、当前发布版本、图层和资源入口，并复算/核对数量与摘要；发现半包时返回结构化 `DEGRADED`，绝不拼接部分快照。响应不包含宿主机路径、存储键、创建人敏感信息或任意外链。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=SceneRuntimeIT -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene docs/openapi/mine-api.yaml mine-server/src/test/java/com/bosco/mine/server/integration/SceneRuntimeIT.java
git commit -m "feat(scene): 提供最后正确场景运行快照"
```

### Task 8：隔离 Cesium 依赖、Worker 和离线静态资源

**Files:**
- Modify: `mine-web/package.json`
- Modify: `mine-web/pnpm-lock.yaml`
- Modify: `mine-web/vite.config.ts`
- Create: `mine-web/build/plugins/cesium.ts`
- Modify: `mine-web/build/plugins/index.ts`
- Modify: `mine-web/src/typings/vite-env.d.ts`
- Create: `mine-web/src/modules/scene3d/runtime/load-cesium.ts`
- Test: `mine-web/src/modules/scene3d/runtime/load-cesium.test.ts`
- Create: `mine-web/src/test/cesium-bundle-boundary.test.ts`

**Step 1: Write failing loader and build-boundary tests**

断言 `loadCesium()` 只在调用时执行动态 import、设置同源 `CESIUM_BASE_URL`、不设置 ion token；静态源码扫描保证动态路由和非 scene3d 模块不导入 `cesium`；构建配置复制 `Workers/Assets/Widgets/ThirdParty` 到离线包且 Cesium 位于独立异步 chunk。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/runtime/load-cesium.test.ts src/test/cesium-bundle-boundary.test.ts`

Expected: FAIL，Cesium 依赖和加载器不存在。

**Step 3: Add pinned dependency and isolated build plugin**

固定 `cesium@1.141.0`。构建插件只复制 Cesium 必需静态目录，拒绝静态资源缺失；`load-cesium.ts` 是唯一允许导入 Cesium 根包的入口。不得在全局样式加载 Cesium Widgets CSS，由三维组件进入时异步加载。

**Step 4: Verify unit and production build**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/runtime/load-cesium.test.ts src/test/cesium-bundle-boundary.test.ts && pnpm --dir mine-web build`

Expected: PASS；构建产物包含独立 Cesium chunk 和本地静态资源，普通入口 chunk 不包含 Cesium 标识。

**Step 5: Commit**

```bash
git add mine-web/package.json mine-web/pnpm-lock.yaml mine-web/vite.config.ts mine-web/build mine-web/src/typings mine-web/src/modules/scene3d/runtime mine-web/src/test/cesium-bundle-boundary.test.ts
git commit -m "feat(frontend): 隔离 Cesium 离线运行依赖"
```

### Task 9：建立场景前端契约和二维独立适配器

**Files:**
- Create: `mine-web/src/modules/scene3d/api/scene.ts`
- Create: `mine-web/src/modules/scene3d/api/scene.test.ts`
- Create: `mine-web/src/modules/scene3d/runtime/types.ts`
- Create: `mine-web/src/modules/scene3d/components/SceneViewport.vue`
- Create: `mine-web/src/modules/scene3d/components/CanvasSceneViewport.vue`
- Test: `mine-web/src/modules/scene3d/components/SceneViewport.test.ts`
- Create: `mine-web/src/modules/mine/index.ts`

**Step 1: Write failing contract and fallback tests**

覆盖 API 路径/分页/幂等头/上传 FormData、运行快照解析、`AUTO/3D/2D` 模式、WebGL 不可用直接二维、动态 import 失败切二维、二维不调用 Cesium loader、重试成功切回三维、卸载释放适配器。用 mock adapter 做行为断言，不使用大快照。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/api/scene.test.ts src/modules/scene3d/components/SceneViewport.test.ts`

Expected: FAIL，场景 API 和视口不存在。

**Step 3: Implement public mine exports and independent fallback**

`modules/mine/index.ts` 只导出位置/空间公共类型、composable 和 `MineCoordinateCanvas`；scene3d 不导入 mine 模块内部页面。`CanvasSceneViewport` 只依赖公共接口和 ECharts，显示局部坐标、位置、区域、事件和降级原因。

**Step 4: Verify GREEN**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/api/scene.test.ts src/modules/scene3d/components/SceneViewport.test.ts`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-web/src/modules/scene3d mine-web/src/modules/mine/index.ts
git commit -m "feat(frontend): 建立三维视口与二维独立降级"
```

### Task 10：交付场景资源和版本治理工作台

**Files:**
- Create: `mine-web/src/modules/scene3d/pages/SceneWorkbenchPage.vue`
- Test: `mine-web/src/modules/scene3d/pages/SceneWorkbenchPage.test.ts`
- Create: `mine-web/src/modules/scene3d/components/SceneAssetPanel.vue`
- Create: `mine-web/src/modules/scene3d/components/SceneVersionEditor.vue`
- Test: `mine-web/src/modules/scene3d/components/SceneVersionEditor.test.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.ts`
- Modify: `mine-web/src/app/router/dynamic-routes.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Step 1: Write failing page, permission and route tests**

覆盖 `mine-scene-workbench` 白名单、只读/编辑/上传/发布权限、矿区切换取消旧请求、资源上传进度/错误、草稿未保存保护、图层排序/类型与资源筛选、发布影响确认、409 刷新、默认场景切换和无发布版本空状态。所有可见文本来自中英文资源。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/pages/SceneWorkbenchPage.test.ts src/modules/scene3d/components/SceneVersionEditor.test.ts src/app/router/dynamic-routes.test.ts`

Expected: FAIL，页面和路由未实现。

**Step 3: Implement with Naive UI and UnoCSS only**

页面使用“运行场景/场景治理”页签；禁止 `<style>`、内联 CSS、硬编码颜色和硬编码文案。上传限制和归档/发布影响必须在提交前可见；发布成功后重新读取运行快照，不用浏览器草稿伪装已发布。

**Step 4: Run frontend gates**

Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check && pnpm --dir mine-web test:unit -- src/modules/scene3d/pages/SceneWorkbenchPage.test.ts src/modules/scene3d/components/SceneVersionEditor.test.ts src/app/router/dynamic-routes.test.ts`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-web/src/modules/scene3d mine-web/src/app/router mine-web/src/locales/langs
git commit -m "feat(frontend): 交付场景资源与版本治理工作台"
```

### Task 11：实现本地 Cesium 场景和静态图层

**Files:**
- Create: `mine-web/src/modules/scene3d/components/CesiumSceneViewport.vue`
- Create: `mine-web/src/modules/scene3d/runtime/CesiumSceneAdapter.ts`
- Test: `mine-web/src/modules/scene3d/runtime/CesiumSceneAdapter.test.ts`
- Create: `mine-web/src/modules/scene3d/runtime/scene-transform.ts`
- Test: `mine-web/src/modules/scene3d/runtime/scene-transform.test.ts`

**Step 1: Write failing adapter tests**

mock 最小 Cesium API，覆盖无 ion/无远程底图、`requestRenderMode`、局部 ENU/WGS84 变换、GRID、GLB、3D Tiles 有序加载、同源资源 URL、透明度/可见距离、资源失败只降级对应图层、场景关键初始化失败通知宿主二维降级和 `destroy()` 完整释放。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/runtime/CesiumSceneAdapter.test.ts src/modules/scene3d/runtime/scene-transform.test.ts`

Expected: FAIL，Cesium 适配器不存在。

**Step 3: Implement minimal production adapter**

Vue 组件只负责容器生命周期和状态；Cesium 调用集中在 adapter。关闭动画、时间轴、搜索、在线地形和默认影像；相机从发布快照读取，失败使用有界局部视口。图层错误通过回调显示，不在适配器内直接调用消息组件。

**Step 4: Verify unit and build**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/runtime/CesiumSceneAdapter.test.ts src/modules/scene3d/runtime/scene-transform.test.ts && pnpm --dir mine-web build`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-web/src/modules/scene3d/components/CesiumSceneViewport.vue mine-web/src/modules/scene3d/runtime
git commit -m "feat(frontend): 加载本地 Cesium 场景与静态图层"
```

### Task 12：增加高频位置、区域、事件和轨迹叠加

**Files:**
- Create: `mine-web/src/modules/scene3d/composables/useSceneRealtime.ts`
- Test: `mine-web/src/modules/scene3d/composables/useSceneRealtime.test.ts`
- Create: `mine-web/src/modules/scene3d/runtime/CesiumDynamicLayerAdapter.ts`
- Test: `mine-web/src/modules/scene3d/runtime/CesiumDynamicLayerAdapter.test.ts`
- Modify: `mine-web/src/modules/scene3d/pages/SceneWorkbenchPage.vue`

**Step 1: Write failing realtime and pool tests**

覆盖 REST 快照后接位置/空间增量、重复序号幂等、缺口/重连回查、矿区切换隔离；Cesium 对象池按对象 ID 复用、动画帧合并、视距/上限裁剪、区域版本不变不重建、选择对象才加载有界轨迹、低质量/陈旧状态可见、卸载释放订阅和 Primitive。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/composables/useSceneRealtime.test.ts src/modules/scene3d/runtime/CesiumDynamicLayerAdapter.test.ts`

Expected: FAIL，实时场景层不存在。

**Step 3: Implement bounded dynamic layers**

复用 RealtimeHub 和矿区公共 composable，不创建第二个 WebSocket，不将高频点写入 Pinia。3D 和 2D 接收同一不可变视图模型；位置、空间事件和轨迹各自独立降级，页面保留最后正确数据及更新时间。

**Step 4: Verify GREEN and performance contracts**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/composables/useSceneRealtime.test.ts src/modules/scene3d/runtime/CesiumDynamicLayerAdapter.test.ts`

Expected: PASS；测试证明相同对象 100 次更新只创建一次 Primitive，超限数据不创建额外对象。

**Step 5: Commit**

```bash
git add mine-web/src/modules/scene3d
git commit -m "feat(frontend): 叠加矿区实时位置与空间事实"
```

### Task 13：增加告警和生产运营联动及独立故障状态

**Files:**
- Create: `mine-web/src/modules/scene3d/composables/useSceneBusinessLayers.ts`
- Test: `mine-web/src/modules/scene3d/composables/useSceneBusinessLayers.test.ts`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/alarm/controller/MineAlarmQueryController.java`
- Create: `mine-server/src/main/resources/db/migration/V52__publish_mine_alarm_access.sql`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/alarm/service/AlarmEventService.java`
- Modify: `docs/openapi/mine-api.yaml`
- Modify: `mine-web/src/modules/scene3d/runtime/CesiumDynamicLayerAdapter.ts`
- Modify: `mine-web/src/modules/scene3d/pages/SceneWorkbenchPage.vue`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Step 1: Write failing business layer tests**

覆盖未关闭车载设备告警按 `sourceDeviceId` 映射当前位置、无位置告警留在侧栏而不伪造坐标、矿区作用域在分页前强制隔离、运营 `businessSequence` 去重/缺口回查及回查期间竞态、当前班次/运行作业摘要、告警 API 失败不影响位置、运营实时失败不影响三维资源、三维资源失败二维仍显示业务层。

**Step 2: Verify RED**

Run: `pnpm --dir mine-web test:unit -- src/modules/scene3d/composables/useSceneBusinessLayers.test.ts`

Expected: FAIL，业务层 composable 不存在。

**Step 3: Implement alarm and operation projections**

只使用受权告警、运营 API 和 RealtimeHub；不要为页面新增无权限的设备全集调用。全局告警中心 `/alarms` 与场景矿区告警 `/mine/sites/{siteId}/alarms` 保持独立权限边界，场景接口必须强制校验矿区作用域。当前可信资产真相只覆盖历史有效期车辆设备绑定，因此场景告警明确限定为车载设备；固定设备必须等待统一资产-矿区归属域，禁止为了显示标记而建第二套临时映射真相。告警、运营、位置、空间和资源分别显示 `READY/RESYNCING/DEGRADED`，任一失败不能把其他层判死。

**Step 4: Run frontend gates**

Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check && pnpm --dir mine-web test:unit`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-web/src/modules/scene3d mine-web/src/locales/langs
git commit -m "feat(scene): 联动矿区告警与生产运营"
```

### Task 14：增加确定性演示资源和真实浏览器降级回归

**Files:**
- Create: `scripts/generate-demo-scene-assets.mjs`
- Create: `deploy/demo/scene/README.md`
- Create: `deploy/demo/scene/demo-mine-grid.glb`
- Create: `mine-web/e2e/mine-scene-workbench.spec.ts`
- Modify: `mine-device-simulator-app/README.md`
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/tests/compose-config.sh`

**Step 1: Write failing generator and Playwright scenarios**

生成器测试通过运行两次并校验 SHA-256 完全一致；E2E 覆盖授权菜单、加载本地 demo、位置增量更新、资源 500 只降级图层、WebGL 初始化失败自动二维、二维仍能切矿区/查看位置/区域/告警/运营，以及非三维路由没有请求 Cesium chunk。

**Step 2: Verify RED**

Run: `node scripts/generate-demo-scene-assets.mjs --check && pnpm --dir mine-web test:e2e -- mine-scene-workbench.spec.ts`

Expected: FAIL，演示资产和场景页面尚未接入。

**Step 3: Implement deterministic offline demo**

脚本生成只含公开自制几何的最小 GLB，写入固定元数据和摘要，不下载网络素材。Standalone 挂载场景资源持久卷并显式配置上传上限/存储根；模拟定位继续使用已有双坐标和运输场景。

**Step 4: Verify browser and Compose contracts**

Run: `node scripts/generate-demo-scene-assets.mjs --check && bash deploy/tests/compose-config.sh && pnpm --dir mine-web test:e2e -- mine-scene-workbench.spec.ts`

Expected: PASS。

**Step 5: Commit**

```bash
git add scripts/generate-demo-scene-assets.mjs deploy/demo/scene mine-web/e2e/mine-scene-workbench.spec.ts mine-device-simulator-app/README.md deploy/compose/compose-standalone.yml deploy/tests/compose-config.sh
git commit -m "feat(scene): 增加确定性三维演示与降级回归"
```

### Task 15：增加场景存储健康、指标和告警

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/SceneMetrics.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene/storage/SceneAssetStorageHealthIndicator.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/SceneMetricsTest.java`
- Test: `mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene/storage/SceneAssetStorageHealthIndicatorTest.java`
- Modify: `deploy/config/prometheus/rules/platform.yml`
- Modify: `deploy/config/prometheus/tests/platform.test.yml`
- Modify: `deploy/tests/prometheus-rules.sh`

**Step 1: Write failing metrics and PromQL tests**

固定指标标签只允许 `stage/result/reason/assetType`。测试上传/校验/发布/读取计数只在事务提交后增加，存储读取失败不泄漏路径/异常，Gauge 数据库/存储不可用返回 `NaN` 或明确健康降级且不影响业务异常处理。PromQL 覆盖存储不可用、读取失败率、隔离资源突增和默认场景快照失败的阈值、最小样本和 `for`。

**Step 2: Verify RED**

Run: `./mvnw -pl mine-server -am -Dtest=SceneMetricsTest,SceneAssetStorageHealthIndicatorTest -Dsurefire.failIfNoSpecifiedTests=false test && bash deploy/tests/prometheus-rules.sh`

Expected: FAIL，指标和告警未定义。

**Step 3: Implement low-cardinality observability**

禁止组织、矿区、场景、资源、文件路径、用户、异常类型/文本进入标签。健康检查只创建/读取/删除专用探针文件，不扫描整个对象树；超时有界。告警说明明确三维故障先降级二维，不能清资源或修改发布事实。

**Step 4: Verify GREEN**

Run: `./mvnw -pl mine-server -am -Dtest=SceneMetricsTest,SceneAssetStorageHealthIndicatorTest -Dsurefire.failIfNoSpecifiedTests=false test && bash deploy/tests/prometheus-rules.sh`

Expected: PASS。

**Step 5: Commit**

```bash
git add mine-server/src/main/java/com/bosco/mine/server/modules/mine/scene mine-server/src/test/java/com/bosco/mine/server/modules/mine/scene deploy/config/prometheus deploy/tests/prometheus-rules.sh
git commit -m "feat(scene): 增加三维场景运行监控"
```

### Task 16：完成全仓验收、运行手册和路线图收口

**Files:**
- Create: `docs/runbooks/mine-scene-runtime.md`
- Create: `docs/reports/phase-3-scene-acceptance.md`
- Modify: `docs/06-mine-business.md`
- Modify: `docs/07-frontend.md`
- Modify: `docs/11-contracts-and-database.md`
- Modify: `docs/12-roadmap-and-codex-workflow.md`
- Modify: `docs/13-technology-and-licenses.md`
- Modify: `docs/README.md`
- Modify: `deploy/README.md`

**Step 1: Run complete automated gates**

Run:

```bash
./mvnw -B -ntp test
./mvnw -B -ntp verify -DskipUnitTests
pnpm --dir mine-web check:ui-governance
pnpm --dir mine-web typecheck
pnpm --dir mine-web lint:check
pnpm --dir mine-web test:unit
pnpm --dir mine-web build
pnpm --dir mine-web test:e2e -- mine-scene-workbench.spec.ts
bash deploy/tests/compose-config.sh
bash deploy/tests/prometheus-rules.sh
```

Expected: 全部 PASS；从本次 Surefire/Failsafe/Vitest/Prometheus 输出记录精确数量，不复用旧报告数字。

**Step 2: Write the operations runbook**

覆盖上传上限和卷准备、场景草稿/发布/默认切换、确定性 demo、三维/二维切换、资源/Worker/实时状态核查、存储/读取/快照告警、内容寻址孤儿对账、备份恢复边界和安全回退。明确禁止替换摘要内容、修改发布版本、清资源卷或用任意外链绕过 API。

**Step 3: Write acceptance report and product truth**

验收报告区分 `AUTOMATED PASS` 与 `PRODUCTION NOT RELEASED`。把真实模型/坐标、GPU/浏览器、共享存储/S3、目标容量、HA、备份恢复、弱网和 24/72 小时长稳列为 `DEFERRED`。路线图把 Cesium/二维降级改为自动化通过，并进入 Phase 4 商用加固。

**Step 4: Validate documentation and final gates**

Run: `git diff --check`，并检查所有新增相对 Markdown 链接存在；若文档之外有代码变化，重跑对应门禁。

Expected: PASS，文档不把仓库自动化解释为现场生产资格。

**Step 5: Request review and commit**

按 `@requesting-code-review` 复核安全上传、路径/Range、发布不可变性、Cesium chunk 边界、实时水位、二维独立降级、指标基数和延期声明，修复问题后提交：

```bash
git add docs deploy/README.md
git commit -m "docs(scene): 完成三维场景自动化验收"
```

---

Plan complete and saved to `docs/plans/2026-07-20-scene-3d-implementation.md`。当前用户已明确要求按推荐路线持续推进，因此默认在当前专用功能分支使用 `@executing-plans` 逐任务执行、每个任务独立验证和提交；不创建额外用户任务或并行代理。
