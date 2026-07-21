package com.bosco.mine.telemetry.aggregation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验数值聚合执行器的锁竞争、有界批次、失联恢复、迟到计数和失败脱敏。
 */
class AggregationExecutorTest {

    private static final UUID EXECUTION_ID =
            UUID.fromString("019f6f00-0000-7000-8000-000000000801");

    @Test
    void 未获得数据库单实例锁时不得创建执行事实() {
        FakeStore store = new FakeStore(false, new FakeSession());

        AggregationExecutor.RunResult result = executor(store, 100, 3).runOnce();

        assertThat(result.status()).isEqualTo(AggregationExecutor.RunStatus.SKIPPED_LOCKED);
        assertThat(result.executionId()).isNull();
        assertThat(store.session.startedExecutionId).isNull();
    }

    @Test
    void 无启用策略时应恢复失联事实并成功留下空执行() {
        FakeSession session = new FakeSession();
        session.plan = new AggregationExecutor.Plan(42, 40, 0, 0);

        AggregationExecutor.RunResult result =
                executor(new FakeStore(true, session), 100, 3).runOnce();

        assertThat(result.status()).isEqualTo(AggregationExecutor.RunStatus.SUCCEEDED);
        assertThat(result.sourceWatermark()).isEqualTo(42);
        assertThat(result.bucketCount()).isZero();
        assertThat(session.recoverCalls).isOne();
        assertThat(session.batchCalls).isZero();
        assertThat(session.completedStatus).isEqualTo(AggregationExecutor.RunStatus.SUCCEEDED);
    }

    @Test
    void 扫描完闭合桶时应累计版本和超窗口迟到行数() {
        FakeSession session = new FakeSession();
        session.plan = new AggregationExecutor.Plan(51, 43, 2, 3);
        session.batches.add(100);
        session.batches.add(20);
        session.lateRowsIgnored = 4;

        AggregationExecutor.RunResult result =
                executor(new FakeStore(true, session), 100, 3).runOnce();

        assertThat(result.status()).isEqualTo(AggregationExecutor.RunStatus.SUCCEEDED);
        assertThat(result.policyCount()).isEqualTo(2);
        assertThat(result.ruleCount()).isEqualTo(3);
        assertThat(result.bucketCount()).isEqualTo(120);
        assertThat(result.lateRowsIgnored()).isEqualTo(4);
        assertThat(session.completedStatus).isEqualTo(AggregationExecutor.RunStatus.SUCCEEDED);
        assertThat(session.completedLateRowsIgnored).isEqualTo(4);
    }

    @Test
    void 达到单轮周期上限时应留下可继续执行的部分完成事实() {
        FakeSession session = new FakeSession();
        session.plan = new AggregationExecutor.Plan(60, 51, 1, 1);
        session.batches.add(10);
        session.batches.add(10);

        AggregationExecutor.RunResult result =
                executor(new FakeStore(true, session), 10, 2).runOnce();

        assertThat(result.status()).isEqualTo(AggregationExecutor.RunStatus.PARTIAL);
        assertThat(result.bucketCount()).isEqualTo(20);
        assertThat(session.completedStatus).isEqualTo(AggregationExecutor.RunStatus.PARTIAL);
    }

    @Test
    void 聚合异常应保留已提交进度并收敛为脱敏失败事实() {
        FakeSession session = new FakeSession();
        session.plan = new AggregationExecutor.Plan(70, 60, 1, 1);
        session.batches.add(100);
        session.failureAfterCalls = 1;
        session.failure = new IllegalStateException(
                "password=mine-secret token=service-token\nquery failed");

        AggregationExecutor.RunResult result =
                executor(new FakeStore(true, session), 100, 3).runOnce();

        assertThat(result.status()).isEqualTo(AggregationExecutor.RunStatus.FAILED);
        assertThat(result.bucketCount()).isEqualTo(100);
        assertThat(result.errorSummary())
                .contains("password=[REDACTED]", "token=[REDACTED]", "query failed")
                .doesNotContain("mine-secret", "service-token", "\n");
        assertThat(session.completedStatus).isEqualTo(AggregationExecutor.RunStatus.FAILED);
        assertThat(session.completedError).isEqualTo(result.errorSummary());
    }

    @Test
    void 应拒绝可能导致无界执行的参数和非法仓储计数() {
        assertThatThrownBy(() -> executor(
                new FakeStore(true, new FakeSession()), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregationExecutor(
                new FakeStore(true, new FakeSession()),
                "telemetry-a", 10, 1, Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);

        FakeSession session = new FakeSession();
        session.plan = new AggregationExecutor.Plan(1, 0, 1, 1);
        session.batches.add(11);
        AggregationExecutor.RunResult result =
                executor(new FakeStore(true, session), 10, 1).runOnce();
        assertThat(result.status()).isEqualTo(AggregationExecutor.RunStatus.FAILED);
        assertThat(result.errorSummary()).contains("非法批次桶数");
    }

    private AggregationExecutor executor(
            AggregationExecutor.Store store,
            int batchSize,
            int maxCycles
    ) {
        return new AggregationExecutor(
                store,
                "telemetry-a",
                batchSize,
                maxCycles,
                Duration.ofHours(2),
                () -> EXECUTION_ID
        );
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
        private final Queue<Integer> batches = new ArrayDeque<>();
        private AggregationExecutor.Plan plan = new AggregationExecutor.Plan(0, 0, 0, 0);
        private int recoverCalls;
        private int batchCalls;
        private int failureAfterCalls = Integer.MAX_VALUE;
        private RuntimeException failure;
        private long lateRowsIgnored;
        private UUID startedExecutionId;
        private AggregationExecutor.RunStatus completedStatus;
        private long completedLateRowsIgnored;
        private String completedError;

        @Override
        public int recoverStaleExecutions(Duration timeout) {
            recoverCalls++;
            return 0;
        }

        @Override
        public AggregationExecutor.Plan loadPlan() {
            return plan;
        }

        @Override
        public void startExecution(
                UUID executionId,
                String instanceId,
                AggregationExecutor.Plan executionPlan
        ) {
            startedExecutionId = executionId;
        }

        @Override
        public int aggregateNextBatch(UUID executionId, int limit) {
            if (batchCalls >= failureAfterCalls) throw failure;
            batchCalls++;
            return batches.isEmpty() ? 0 : batches.remove();
        }

        @Override
        public int writeRollupBatch(
                UUID executionId,
                java.util.List<AggregationExecutionRepository.RollupWrite> rollups
        ) {
            return rollups.size();
        }

        @Override
        public long countIgnoredLateRows(
                UUID executionId,
                AggregationExecutor.Plan executionPlan
        ) {
            return lateRowsIgnored;
        }

        @Override
        public void completeExecution(
                UUID executionId,
                AggregationExecutor.RunStatus status,
                long ignoredLateRows,
                String errorSummary
        ) {
            completedStatus = status;
            completedLateRowsIgnored = ignoredLateRows;
            completedError = errorSummary;
        }
    }
}
