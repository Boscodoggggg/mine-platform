package com.bosco.mine.telemetry.aggregation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验显式聚合重算的有界输入、稳定重放和失败关闭语义。
 */
class AggregationRecomputeExecutorTest {

    private static final UUID REQUEST_ID = uuid(801);
    private static final UUID EXECUTION_ID = uuid(802);
    private static final UUID DEVICE_ID = uuid(803);
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void 应在聚合锁内追加有界重算版本并完成稳定执行() {
        FakeSession session = new FakeSession();
        session.preparation = new AggregationRecomputeExecutor.Preparation(
                AggregationRecomputeExecutor.PreparationStatus.READY,
                51, 2, null);
        session.recomputed = 3;

        AggregationRecomputeExecutor.RunResult result =
                executor(new FakeStore(true, session)).run(request());

        assertThat(result.status())
                .isEqualTo(AggregationRecomputeExecutor.RunStatus.SUCCEEDED);
        assertThat(result.sourceWatermark()).isEqualTo(51);
        assertThat(result.bucketCount()).isEqualTo(5);
        assertThat(session.preparedRequest).isEqualTo(request());
        assertThat(session.recomputedRequest).isEqualTo(request());
        assertThat(session.completedStatus).isEqualTo(AggregationExecutor.RunStatus.SUCCEEDED);
    }

    @Test
    void 聚合锁忙时应无副作用跳过() {
        FakeSession session = new FakeSession();

        AggregationRecomputeExecutor.RunResult result =
                executor(new FakeStore(false, session)).run(request());

        assertThat(result.status())
                .isEqualTo(AggregationRecomputeExecutor.RunStatus.SKIPPED_LOCKED);
        assertThat(session.preparedRequest).isNull();
    }

    @Test
    void 完成响应丢失后应重放同一成功终态而不重复计算() {
        FakeSession session = new FakeSession();
        session.preparation = new AggregationRecomputeExecutor.Preparation(
                AggregationRecomputeExecutor.PreparationStatus.SUCCEEDED,
                52, 4, null);

        AggregationRecomputeExecutor.RunResult result =
                executor(new FakeStore(true, session)).run(request());

        assertThat(result.status())
                .isEqualTo(AggregationRecomputeExecutor.RunStatus.SUCCEEDED);
        assertThat(result.bucketCount()).isEqualTo(4);
        assertThat(session.recomputedRequest).isNull();
        assertThat(session.completedStatus).isNull();
    }

    @Test
    void 规则失效时应关闭执行并保留脱敏失败事实() {
        FakeSession session = new FakeSession();
        session.preparation = new AggregationRecomputeExecutor.Preparation(
                AggregationRecomputeExecutor.PreparationStatus.REJECTED,
                53, 0, "token=service-secret 当前策略不再匹配");

        AggregationRecomputeExecutor.RunResult result =
                executor(new FakeStore(true, session)).run(request());

        assertThat(result.status())
                .isEqualTo(AggregationRecomputeExecutor.RunStatus.FAILED);
        assertThat(result.errorSummary())
                .contains("token=[REDACTED]", "当前策略不再匹配")
                .doesNotContain("service-secret");
        assertThat(session.completedStatus).isEqualTo(AggregationExecutor.RunStatus.FAILED);
        assertThat(session.completedError).isEqualTo(result.errorSummary());
    }

    @Test
    void 计算异常应收敛为单行脱敏失败事实() {
        FakeSession session = new FakeSession();
        session.preparation = new AggregationRecomputeExecutor.Preparation(
                AggregationRecomputeExecutor.PreparationStatus.READY,
                54, 0, null);
        session.failure = new IllegalStateException(
                "password=mine-secret\n重算查询失败");

        AggregationRecomputeExecutor.RunResult result =
                executor(new FakeStore(true, session)).run(request());

        assertThat(result.status())
                .isEqualTo(AggregationRecomputeExecutor.RunStatus.FAILED);
        assertThat(result.errorSummary())
                .contains("password=[REDACTED]", "重算查询失败")
                .doesNotContain("mine-secret", "\n");
        assertThat(session.completedStatus).isEqualTo(AggregationExecutor.RunStatus.FAILED);
    }

    @Test
    void 已失败的稳定执行应直接重放失败终态() {
        FakeSession session = new FakeSession();
        session.preparation = new AggregationRecomputeExecutor.Preparation(
                AggregationRecomputeExecutor.PreparationStatus.FAILED,
                55, 1, "上次重算已经失败");

        AggregationRecomputeExecutor.RunResult result =
                executor(new FakeStore(true, session)).run(request());

        assertThat(result.status())
                .isEqualTo(AggregationRecomputeExecutor.RunStatus.FAILED);
        assertThat(result.errorSummary()).isEqualTo("上次重算已经失败");
        assertThat(session.recomputedRequest).isNull();
        assertThat(session.completedStatus).isNull();
    }

    @Test
    void 应拒绝非对齐未来和超过一万桶的请求() {
        assertThatThrownBy(() -> executor(new FakeStore(true, new FakeSession())).run(
                new AggregationRecomputeExecutor.Request(
                        REQUEST_ID, EXECUTION_ID, DEVICE_ID, "engine.rpm",
                        Duration.ofMinutes(5), NOW.minusSeconds(301), NOW.minusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTC 对齐");
        assertThatThrownBy(() -> executor(new FakeStore(true, new FakeSession())).run(
                new AggregationRecomputeExecutor.Request(
                        REQUEST_ID, EXECUTION_ID, DEVICE_ID, "engine.rpm",
                        Duration.ofMinutes(5), NOW.minusSeconds(300), NOW.plusSeconds(300))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未来");
        assertThatThrownBy(() -> executor(new FakeStore(true, new FakeSession())).run(
                new AggregationRecomputeExecutor.Request(
                        REQUEST_ID, EXECUTION_ID, DEVICE_ID, "engine.rpm",
                        Duration.ofMinutes(1), NOW.minusSeconds(60L * 10_001), NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10000");
    }

    private AggregationRecomputeExecutor executor(AggregationExecutor.Store store) {
        return new AggregationRecomputeExecutor(store, "telemetry-recompute-a", CLOCK);
    }

    private AggregationRecomputeExecutor.Request request() {
        return new AggregationRecomputeExecutor.Request(
                REQUEST_ID, EXECUTION_ID, DEVICE_ID, "engine.rpm",
                Duration.ofMinutes(5), NOW.minusSeconds(600), NOW);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f6f00-0000-7000-8000-%012d".formatted(suffix));
    }

    private static final class FakeStore implements AggregationExecutor.Store {
        private final boolean locked;
        private final FakeSession session;

        private FakeStore(boolean locked, FakeSession session) {
            this.locked = locked;
            this.session = session;
        }

        @Override
        public <T> Optional<T> withLock(AggregationExecutor.LockedWork<T> work) {
            return locked ? Optional.of(work.execute(session)) : Optional.empty();
        }
    }

    private static final class FakeSession implements AggregationExecutor.Session {
        private AggregationRecomputeExecutor.Preparation preparation;
        private AggregationRecomputeExecutor.Request preparedRequest;
        private AggregationRecomputeExecutor.Request recomputedRequest;
        private int recomputed;
        private RuntimeException failure;
        private AggregationExecutor.RunStatus completedStatus;
        private String completedError;

        @Override
        public AggregationRecomputeExecutor.Preparation prepareRecomputation(
                String instanceId,
                AggregationRecomputeExecutor.Request request
        ) {
            preparedRequest = request;
            return preparation;
        }

        @Override
        public int recompute(
                AggregationRecomputeExecutor.Request request,
                int limit
        ) {
            recomputedRequest = request;
            if (failure != null) throw failure;
            return recomputed;
        }

        @Override
        public int recoverStaleExecutions(Duration timeout) {
            return 0;
        }

        @Override
        public AggregationExecutor.Plan loadPlan() {
            return new AggregationExecutor.Plan(0, 0, 0, 0);
        }

        @Override
        public void startExecution(
                UUID executionId,
                String instanceId,
                AggregationExecutor.Plan executionPlan
        ) {
        }

        @Override
        public int aggregateNextBatch(UUID executionId, int limit) {
            return 0;
        }

        @Override
        public int writeRollupBatch(
                UUID executionId,
                List<AggregationExecutionRepository.RollupWrite> rollups
        ) {
            return 0;
        }

        @Override
        public long countIgnoredLateRows(
                UUID executionId,
                AggregationExecutor.Plan executionPlan
        ) {
            return 0;
        }

        @Override
        public void completeExecution(
                UUID executionId,
                AggregationExecutor.RunStatus status,
                long lateRowsIgnored,
                String errorSummary
        ) {
            completedStatus = status;
            completedError = errorSummary;
        }
    }
}
