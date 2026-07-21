# Aggregation Recomputation Execution Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 把已登记的显式聚合重算请求可靠地认领到 Telemetry，生成更高版本的聚合事实，并把成功或脱敏失败终态回写控制面。

**Architecture:** mine-server 在业务 PostgreSQL 中保存请求、稳定执行标识和可恢复租约，通过已有外部 service token 保护的内部 HTTP 接口提供原子认领与令牌校验终结。Telemetry 使用同一聚合 advisory lock 和当前最后正确策略镜像，在固定原始摄取水位下对有界 UTC 桶范围重算；相同请求始终复用同一执行标识，因此认领或完成响应丢失后可以安全恢复，不会原地覆盖旧 rollup。

**Tech Stack:** Java 25、Spring Boot 4、JDBC、PostgreSQL 17、TimescaleDB 2.x、Flyway、JDK HttpClient、Micrometer、JUnit 5、Testcontainers。

---

## 实施约束

- 控制面认领使用 `FOR UPDATE SKIP LOCKED`，租约到期才能被其他实例重新认领；每次认领更换 claim token，但保留 requestId 与 executionId。
- 完成回写必须同时匹配 requestId、RUNNING 状态和当前 claim token；旧实例的迟到回写不得覆盖新租约。
- Telemetry 必须再次校验当前镜像中存在完全匹配的启用规则；策略变化、请求越界或原始数据不可用时失败关闭。
- 显式重算读取固定 `sourceWatermark` 下全部可见数值样本，包括超出普通修正窗口的迟到样本；结果只追加新 `rollup_revision`。
- 单请求最多 10000 个桶，使用与常规聚合相同的 advisory lock；禁止自由 SQL、表达式和跨设备计算。
- 新增 Java 公共类型与方法使用中文 Javadoc；新表字段使用中文注释并由集成测试校验。

### Task 1：控制面租约认领与安全终结

**Files:**
- Create: `mine-server/src/main/resources/db/migration/V32__lease_aggregation_recompute_requests.sql`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/aggregation/repository/AggregationRecomputeRepository.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/aggregation/dto/InternalAggregationRecomputeClaim.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/aggregation/dto/InternalAggregationRecomputeCompletion.java`
- Create: `mine-server/src/main/java/com/bosco/mine/server/modules/aggregation/controller/InternalAggregationRecomputeController.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/aggregation/service/AggregationGovernanceService.java`
- Modify: `mine-server/src/main/java/com/bosco/mine/server/modules/configuration/security/InternalServiceTokenFilter.java`
- Modify: matching server unit and `DatabaseMigrationIT` tests

**Step 1: 写失败测试**

覆盖并发只认领一次、PENDING 生成稳定 executionId、租约内不重复认领、租约过期更换 token 但复用 executionId、旧 token 完成失败、成功/失败生命周期、字段中文注释和内部接口 service token 边界。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-server -am -Dtest='AggregationRecomputeRepository*Test,InternalAggregationRecomputeControllerTest,DatabaseMigrationIT,InternalServiceTokenFilterTest' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，V32 字段和内部认领接口尚不存在。

**Step 3: 最小实现**

V32 新增 `claimed_by`、`claim_token`、`claimed_at`、`lease_until`、`attempt_count`、`updated_at` 并重建生命周期约束和待认领索引。仓储以单条 CTE 原子认领 PENDING 或租约过期 RUNNING 请求；服务生成 UUIDv7 executionId 和 claim token，内部控制器只返回计算所需的非敏感字段。完成接口将错误转为单行、脱敏、最长 2000 字符的摘要。

**Step 4: GREEN 并提交**

Commit: `feat(aggregation): 建立重算请求可靠租约`

### Task 2：Telemetry 有界版本化重算

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationRecomputeExecutor.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationExecutor.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationExecutionRepository.java`
- Create/Modify: matching unit and TimescaleDB integration tests

**Step 1: 写失败测试**

覆盖当前规则二次校验、UTC 对齐与 10000 桶上限、固定原始水位、纳入超修正窗口迟到样本、版本追加、同 executionId 重试幂等、完成响应丢失后的终态重放、规则变化失败和数据库锁竞争。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-telemetry -Dtest='AggregationRecomputeExecutorTest,AggregationExecutionRepositoryIT' test`

Expected: FAIL，重算执行器和范围查询尚不存在。

**Step 3: 最小实现**

执行器在现有聚合 advisory lock 内检查或恢复稳定 executionId；首次固定摄取水位并创建执行事实，随后针对单设备、属性、桶宽和 `[from,to)` 计算最多 10000 个有样本桶。重算查询不套用普通迟到窗口，但必须使用当前镜像的运行修订、快照和内容哈希；写入继续走已有原子版本/进度事务并收敛为 SUCCEEDED 或 FAILED。

**Step 4: GREEN 并提交**

Commit: `feat(telemetry): 执行版本化显式聚合重算`

### Task 3：内部客户端、调度恢复与观测

**Files:**
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationRecomputeClient.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/HttpAggregationRecomputeClient.java`
- Create: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationRecomputeWorker.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationProperties.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationConfiguration.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationScheduler.java`
- Modify: `mine-telemetry/src/main/java/com/bosco/mine/telemetry/aggregation/AggregationMetrics.java`
- Modify: `mine-telemetry/src/main/resources/application.yml`
- Modify: `deploy/env/standalone.env.example`
- Modify: `deploy/compose/compose-standalone.yml`
- Modify: `deploy/config/prometheus/rules/platform.yml`
- Modify: `deploy/tests/compose-config.sh`
- Modify: matching unit tests

**Step 1: 写失败测试**

覆盖 204 无任务、认领响应严格解码、service token 文件、无重定向、临时/永久 HTTP 错误、执行成功/失败回写、完成响应丢失安全重放、单线程调度隔离和固定低基数指标。

**Step 2: 确认 RED**

Run: `./mvnw -pl mine-telemetry -Dtest='HttpAggregationRecomputeClientTest,AggregationRecomputeWorkerTest,AggregationSchedulerTest,AggregationMetricsTest' test`

Expected: FAIL，内部客户端和工作器尚不存在。

**Step 3: 最小实现**

复用配置分发 service token secret 与无凭据 HTTP(S) 根地址；常规聚合每轮退出后最多认领一个请求，重算异常不得让固定延迟调度永久停止。指标只使用 `status` 固定枚举；新增租约卡死、连续失败和待处理过久告警，不使用设备、属性或请求标识标签。

**Step 4: 完整验证并提交**

Run:

```bash
./mvnw -pl mine-server,mine-telemetry -am test
bash deploy/tests/compose-config.sh
git diff --check
```

Commit: `feat(aggregation): 闭环显式聚合重算运行消费`

### Task 4：运行手册、验收与全仓门禁

继续执行 `docs/plans/2026-07-17-aggregation-runtime-implementation.md` 的 Task 9。验收报告必须分别给出常规物化和显式重算证据，并继续把真实设备、真实数据、容量、HA、备份恢复和长稳标记为 `DEFERRED`。
