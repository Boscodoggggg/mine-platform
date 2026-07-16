# 系统可观测性控制台 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 复用现有 Prometheus 历史指标，将运行监控页升级为可筛选、可查看可用率和状态趋势、并能降级到即时检查的可观测性控制台。

**Architecture:** Prometheus 继续负责周期采集与历史保存，`mine-server` 通过固定 PromQL 模板提供受权限保护的聚合接口，`mine-web` 展示运维结论并按需加载单项趋势。现有 `SystemOperationsService` 保留为手动即时检查，Prometheus 不可用时页面降级而不白屏。

**Tech Stack:** Java 21、Spring Boot 4、Java HttpClient、JUnit 5、Prometheus HTTP API、Docker Compose、Vue 3、TypeScript、Naive UI、ECharts、Vitest、Vue Test Utils。

---

### Task 1: 可观测部署基线

**Files:**
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/config/prometheus/prometheus.yml`
- Modify: `deploy/config/prometheus/rules/platform.yml`
- Modify: `deploy/env/standalone.env.example`
- Modify: `deploy/tests/observability-smoke.sh`
- Modify: `deploy/tests/compose-config.sh`

**Step 1: Write the failing test**

在部署测试中断言 Compose 包含 `redis-exporter`、Prometheus 抓取 `redis` job，且 Prometheus 不再以业务目标健康作为启动前置条件。

**Step 2: Run test to verify it fails**

Run: `bash deploy/tests/compose-config.sh`

Expected: FAIL，提示缺少 Redis exporter 或 Prometheus 仍依赖业务目标健康。

**Step 3: Write minimal implementation**

增加受 `observability` profile 控制的 Redis exporter，使用现有 Redis secret；增加 Prometheus 抓取配置和 Redis 不可用告警。移除 Prometheus 对业务服务与 exporter 的健康依赖，使目标启动失败能表现为 `up = 0`；数据库 exporter 不再等待数据库健康后才启动。

**Step 4: Run test to verify it passes**

Run: `bash deploy/tests/compose-config.sh && bash -n deploy/tests/observability-smoke.sh`

Expected: PASS。

**Step 5: Commit**

```bash
git add deploy
git commit -m "feat(observability): 补齐 Redis 指标采集"
```

### Task 2: Prometheus 固定查询与聚合接口

**Files:**
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/service/OperationsObservabilityQuery.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/service/OperationsObservabilityService.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/infrastructure/PrometheusProperties.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/infrastructure/PrometheusOperationsQuery.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/system/infrastructure/PrometheusConfiguration.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/system/service/OperationsObservabilityServiceTest.java`
- Create: `mine-server/src/test/java/com/bosco/mine/server/modules/system/infrastructure/PrometheusOperationsQueryTest.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/system/controller/SystemOperationsController.java`
- Modify: `mine-server/src/test/java/com/bosco/mine/server/modules/system/controller/SystemOperationsControllerTest.java`
- Modify: `mine-server/src/main/resources/application.yml`

**Step 1: Write the failing service test**

测试固定检查项目、异常优先排序、有效样本可用率、最后状态变化、空历史和查询源不可用降级。查询端口只接收枚举范围与稳定检查项键，不暴露 PromQL 字符串。

**Step 2: Run test to verify it fails**

Run: `./mvnw -pl mine-server -Dtest=OperationsObservabilityServiceTest test`

Expected: FAIL，目标类型尚不存在。

**Step 3: Implement the domain service**

实现 `1h`、`6h`、`24h`、`7d`、`15d` 范围枚举；聚合返回数据源状态、生成时间、总体状态、状态数量和每项可用率、平均/P95 耗时及最后变化时间。缺失数据映射为 `UNKNOWN`，不得映射为健康。

**Step 4: Run service tests**

Run: `./mvnw -pl mine-server -Dtest=OperationsObservabilityServiceTest test`

Expected: PASS。

**Step 5: Write the failing infrastructure test**

使用本地 `HttpServer` 模拟 Prometheus，验证客户端只调用固定 `/api/v1/query` 和 `/api/v1/query_range` 模板，正确解析矩阵响应，并将超时、非 2xx 和非法 JSON 转换为受控不可用结果。

**Step 6: Run test to verify it fails**

Run: `./mvnw -pl mine-server -Dtest=PrometheusOperationsQueryTest test`

Expected: FAIL，Prometheus 客户端尚不存在。

**Step 7: Implement the Prometheus client**

使用 Java `HttpClient` 和 Spring Boot 已有 Jackson `ObjectMapper`。后端根据允许列表生成固定查询，限制范围、步长和响应大小；异常消息不得包含内部 URL 或原始响应。

**Step 8: Add controller tests first**

扩展控制器测试，验证 `GET /api/v1/system/operations/overview?range=24h` 和 `GET /api/v1/system/operations/items/{key}/series?range=24h` 的响应及 `system:operations:view` 权限。

**Step 9: Implement controller endpoints and configuration**

增加配置 `mine.operations.prometheus.enabled/base-url/connect-timeout/request-timeout`。未启用时仍注册返回 `UNAVAILABLE` 的查询实现，保证应用启动与即时检查不受影响。

**Step 10: Run module tests**

Run: `./mvnw -pl mine-server test`

Expected: PASS。

**Step 11: Commit**

```bash
git add mine-server
git commit -m "feat(backend): 提供运行监控历史聚合接口"
```

### Task 3: OpenAPI 契约与生成类型

**Files:**
- Modify: `docs/openapi/mine-api.yaml`
- Modify: `mine-web/src/generated/mine-api.ts`
- Modify: `mine-web/src/typings/app.d.ts`

**Step 1: Add contract definitions**

定义 `OperationsObservabilityOverview`、`OperationsObservabilityItem`、`OperationsObservabilitySeries`、数据源状态、范围和序列点，补充两个只读接口及 400/401/403 响应。

**Step 2: Regenerate TypeScript types**

Run: `pnpm --dir mine-web gen:api`

Expected: 生成类型包含新增路径和 schema。

**Step 3: Run typecheck baseline**

Run: `pnpm --dir mine-web typecheck`

Expected: PASS。

**Step 4: Commit**

```bash
git add docs/openapi/mine-api.yaml mine-web/src/generated/mine-api.ts mine-web/src/typings/app.d.ts
git commit -m "feat(contract): 增加可观测性查询契约"
```

### Task 4: 可观测性控制台页面

**Files:**
- Create: `mine-web/src/modules/system/pages/operations/OperationsTrendChart.vue`
- Create: `mine-web/src/modules/system/pages/operations/OperationsTrendChart.test.ts`
- Modify: `mine-web/src/modules/system/api/operations.ts`
- Modify: `mine-web/src/modules/system/pages/operations/SystemOperationsPage.vue`
- Modify: `mine-web/src/modules/system/pages/operations/SystemOperationsPage.test.ts`
- Modify: `mine-web/src/locales/langs/zh-cn.ts`
- Modify: `mine-web/src/locales/langs/en-us.ts`

**Step 1: Write failing page tests**

测试页面显示总体结论、状态数量、时间范围、筛选器、可用率和 P95 列；异常项排在正常项之前；Prometheus 不可用时显示即时检查降级提示；点击行后按需请求趋势。

**Step 2: Run test to verify it fails**

Run: `pnpm --dir mine-web test src/modules/system/pages/operations/SystemOperationsPage.test.ts`

Expected: FAIL，页面仍是三张等高卡片。

**Step 3: Implement API and console table**

使用 Naive UI 和 UnoCSS 重构页面。顶部为紧凑运行结论，主体使用 `NDataTable`，提供范围、状态、分类和搜索筛选。状态、耗时与单位禁止换行，不编写页面样式或硬编码颜色。

**Step 4: Run page tests**

Run: `pnpm --dir mine-web test src/modules/system/pages/operations/SystemOperationsPage.test.ts`

Expected: PASS。

**Step 5: Write failing trend test**

测试真实序列生成状态时间带和耗时曲线，无样本时展示国际化空状态，并响应主题变化。

**Step 6: Implement the trend component**

使用项目 `useEcharts` hook；图表颜色从 Naive UI 主题 token 读取，不硬编码色值。详情只在展开后加载，避免首屏请求所有序列。

**Step 7: Run focused tests and governance**

Run: `pnpm --dir mine-web test src/modules/system/pages/operations && pnpm --dir mine-web check:ui-governance`

Expected: PASS。

**Step 8: Commit**

```bash
git add mine-web/src/modules/system
git add mine-web/src/locales/langs/zh-cn.ts mine-web/src/locales/langs/en-us.ts
git commit -m "feat(frontend): 重构可观测性运行控制台"
```

### Task 5: 全量验证与交付

**Files:**
- Modify if required: `deploy/README.md`
- Modify if required: `docs/plans/2026-07-16-operations-observability-console-implementation.md`

**Step 1: Run backend verification**

Run: `./mvnw test`

Expected: PASS。

**Step 2: Run frontend verification**

Run: `pnpm --dir mine-web check:ui-governance && pnpm --dir mine-web typecheck && pnpm --dir mine-web lint:check && pnpm --dir mine-web test && pnpm --dir mine-web build`

Expected: PASS。

**Step 3: Run deployment verification**

Run: `bash deploy/tests/compose-config.sh && bash scripts/verify.sh`

Expected: PASS；需要 Docker 或现场 secret 的检查如果无法执行，必须记录明确原因。

**Step 4: Request code review**

按设计文档检查安全边界、Prometheus 查询限制、降级状态、可访问性、主题和国际化。修复全部 Critical 和 Important 问题。

**Step 5: Commit final fixes**

```bash
git add <本任务实际修改文件>
git commit -m "fix(operations): 完善可观测性控制台交付"
```
