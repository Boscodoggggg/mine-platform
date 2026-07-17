package com.bosco.mine.telemetry.retention;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验数据保留执行器的锁竞争、有界批次、终态和失败脱敏编排。
 */
class RetentionExecutorTest {

    private static final UUID EXECUTION_ID =
            UUID.fromString("019f6e00-0000-7000-8000-000000000501");

    @Test
    void 未获得数据库单实例锁时不得创建执行事实() {
        FakeStore store = new FakeStore(false, new FakeSession());
        RetentionExecutor executor = executor(store, 100, 3);

        RetentionExecutor.RunResult result = executor.runOnce();

        assertThat(result.status()).isEqualTo(RetentionExecutor.RunStatus.SKIPPED_LOCKED);
        assertThat(result.executionId()).isNull();
        assertThat(store.session.startedExecutionId).isNull();
    }

    @Test
    void 两个数据集扫描完成时应累计删除量并完成执行事实() {
        FakeSession session = new FakeSession();
        session.policyCount = 3;
        session.rawDeletes.add(100);
        session.rawDeletes.add(20);
        session.evidenceDeletes.add(100);
        session.evidenceDeletes.add(0);
        RetentionExecutor executor = executor(new FakeStore(true, session), 100, 3);

        RetentionExecutor.RunResult result = executor.runOnce();

        assertThat(result.status()).isEqualTo(RetentionExecutor.RunStatus.SUCCEEDED);
        assertThat(result.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(result.policyCount()).isEqualTo(3);
        assertThat(result.rawRowsDeleted()).isEqualTo(120);
        assertThat(result.qualityRowsDeleted()).isEqualTo(100);
        assertThat(result.batchCount()).isEqualTo(3);
        assertThat(session.completedStatus).isEqualTo(RetentionExecutor.RunStatus.SUCCEEDED);
        assertThat(session.recoverCalls).isOne();
        assertThat(session.checkpointCalls).isEqualTo(3);
    }

    @Test
    void 达到单轮批次上限时应留下可继续执行的部分完成事实() {
        FakeSession session = new FakeSession();
        session.policyCount = 1;
        session.rawDeletes.add(10);
        session.rawDeletes.add(10);
        session.evidenceDeletes.add(10);
        session.evidenceDeletes.add(10);
        RetentionExecutor executor = executor(new FakeStore(true, session), 10, 2);

        RetentionExecutor.RunResult result = executor.runOnce();

        assertThat(result.status()).isEqualTo(RetentionExecutor.RunStatus.PARTIAL);
        assertThat(result.rawRowsDeleted()).isEqualTo(20);
        assertThat(result.qualityRowsDeleted()).isEqualTo(20);
        assertThat(result.batchCount()).isEqualTo(4);
        assertThat(session.completedStatus).isEqualTo(RetentionExecutor.RunStatus.PARTIAL);
    }

    @Test
    void 删除异常应收敛为失败事实并脱敏截断错误() {
        FakeSession session = new FakeSession();
        session.policyCount = 1;
        session.failure = new IllegalStateException(
                "password=mine-secret token=service-token\nconnection failed");
        RetentionExecutor executor = executor(new FakeStore(true, session), 100, 3);

        RetentionExecutor.RunResult result = executor.runOnce();

        assertThat(result.status()).isEqualTo(RetentionExecutor.RunStatus.FAILED);
        assertThat(result.errorSummary())
                .contains("password=[REDACTED]", "token=[REDACTED]", "connection failed")
                .doesNotContain("mine-secret", "service-token", "\n");
        assertThat(session.completedStatus).isEqualTo(RetentionExecutor.RunStatus.FAILED);
        assertThat(session.completedError).isEqualTo(result.errorSummary());
    }

    @Test
    void 质量证据删除失败时应保留已完成的原始遥测删除计数() {
        FakeSession session = new FakeSession();
        session.policyCount = 1;
        session.rawDeletes.add(1);
        session.evidenceFailure = new IllegalStateException("quality cleanup failed");
        RetentionExecutor executor = executor(new FakeStore(true, session), 100, 3);

        RetentionExecutor.RunResult result = executor.runOnce();

        assertThat(result.status()).isEqualTo(RetentionExecutor.RunStatus.FAILED);
        assertThat(result.rawRowsDeleted()).isOne();
        assertThat(result.qualityRowsDeleted()).isZero();
        assertThat(result.batchCount()).isOne();
        assertThat(session.completedRawRowsDeleted).isOne();
        assertThat(session.completedBatchCount).isOne();
        assertThat(session.checkpointCalls).isOne();
    }

    private RetentionExecutor executor(
            RetentionExecutor.Store store,
            int batchSize,
            int maxCycles
    ) {
        return new RetentionExecutor(
                store,
                "telemetry-a",
                batchSize,
                maxCycles,
                Duration.ofHours(2),
                () -> EXECUTION_ID
        );
    }

    private static final class FakeStore implements RetentionExecutor.Store {
        private final boolean locked;
        private final FakeSession session;

        private FakeStore(boolean locked, FakeSession session) {
            this.locked = locked;
            this.session = session;
        }

        @Override
        public <T> Optional<T> withLock(RetentionExecutor.LockedWork<T> work) {
            return locked ? Optional.of(work.execute(session)) : Optional.empty();
        }
    }

    private static final class FakeSession implements RetentionExecutor.Session {
        private final Queue<Integer> rawDeletes = new ArrayDeque<>();
        private final Queue<Integer> evidenceDeletes = new ArrayDeque<>();
        private int policyCount;
        private int recoverCalls;
        private int checkpointCalls;
        private RuntimeException failure;
        private RuntimeException evidenceFailure;
        private UUID startedExecutionId;
        private RetentionExecutor.RunStatus completedStatus;
        private String completedError;
        private long completedRawRowsDeleted;
        private int completedBatchCount;

        @Override
        public int recoverStaleExecutions(Duration timeout) {
            recoverCalls++;
            return 0;
        }

        @Override
        public int countEnabledPolicies() {
            return policyCount;
        }

        @Override
        public void startExecution(UUID executionId, String instanceId, int enabledPolicyCount) {
            startedExecutionId = executionId;
        }

        @Override
        public int deleteRawTelemetryBatch(int limit) {
            if (failure != null) throw failure;
            return rawDeletes.isEmpty() ? 0 : rawDeletes.remove();
        }

        @Override
        public int deleteQualityEvidenceBatch(int limit) {
            if (evidenceFailure != null) throw evidenceFailure;
            return evidenceDeletes.isEmpty() ? 0 : evidenceDeletes.remove();
        }

        @Override
        public void checkpointExecution(
                UUID executionId,
                long rawRowsDeleted,
                long qualityRowsDeleted,
                int batchCount
        ) {
            checkpointCalls++;
        }

        @Override
        public void completeExecution(
                UUID executionId,
                RetentionExecutor.RunStatus status,
                long rawRowsDeleted,
                long qualityRowsDeleted,
                int batchCount,
                String errorSummary
        ) {
            completedStatus = status;
            completedError = errorSummary;
            completedRawRowsDeleted = rawRowsDeleted;
            completedBatchCount = batchCount;
        }
    }
}
