package com.bosco.mine.telemetry.retention;

import com.bosco.mine.common.id.IdGenerator;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 在数据库单实例锁内编排有界数据保留批次并收敛持久执行事实。
 */
public final class RetentionExecutor {

    private static final int MAX_BATCH_SIZE = 100_000;
    private static final int MAX_CYCLES = 1_000;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|token|secret|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final Store store;
    private final String instanceId;
    private final int batchSize;
    private final int maxCycles;
    private final Duration staleExecutionTimeout;
    private final Supplier<UUID> executionIds;

    /**
     * 创建使用 UUIDv7 执行标识的数据保留执行器。
     *
     * @param store 数据保留持久存储
     * @param instanceId 当前 Telemetry 实例标识
     * @param batchSize 单个数据集单批最大删除行数
     * @param maxCycles 单轮最多执行的双数据集批次周期
     * @param staleExecutionTimeout 运行事实失联超时
     */
    public RetentionExecutor(
            Store store,
            String instanceId,
            int batchSize,
            int maxCycles,
            Duration staleExecutionTimeout
    ) {
        this(
                store, instanceId, batchSize, maxCycles, staleExecutionTimeout,
                IdGenerator.timeOrdered()::next
        );
    }

    /** 创建可注入确定性执行标识的测试构造器。 */
    RetentionExecutor(
            Store store,
            String instanceId,
            int batchSize,
            int maxCycles,
            Duration staleExecutionTimeout,
            Supplier<UUID> executionIds
    ) {
        this.store = Objects.requireNonNull(store, "数据保留存储不能为空");
        this.instanceId = requireText(instanceId, "实例标识");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("数据保留批次大小必须在 1 到 100000 之间");
        }
        if (maxCycles < 1 || maxCycles > MAX_CYCLES) {
            throw new IllegalArgumentException("数据保留单轮周期必须在 1 到 1000 之间");
        }
        if (staleExecutionTimeout == null
                || staleExecutionTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("数据保留失联超时必须至少一秒");
        }
        this.batchSize = batchSize;
        this.maxCycles = maxCycles;
        this.staleExecutionTimeout = staleExecutionTimeout;
        this.executionIds = Objects.requireNonNull(executionIds, "执行标识生成器不能为空");
    }

    /**
     * 尝试执行一轮有界数据保留任务。
     *
     * @return 锁忙跳过、成功、部分完成或失败的稳定结果
     */
    public RunResult runOnce() {
        return store.withLock(this::runLocked).orElseGet(RunResult::skippedLocked);
    }

    private RunResult runLocked(Session session) {
        session.recoverStaleExecutions(staleExecutionTimeout);
        int policyCount = session.countEnabledPolicies();
        UUID executionId = Objects.requireNonNull(
                executionIds.get(), "执行标识生成器不得返回空值");
        session.startExecution(executionId, instanceId, policyCount);
        long rawRowsDeleted = 0;
        long qualityRowsDeleted = 0;
        int batchCount = 0;
        try {
            if (policyCount == 0) {
                session.completeExecution(
                        executionId, RunStatus.SUCCEEDED, 0, 0, 0, null);
                return new RunResult(
                        executionId, RunStatus.SUCCEEDED, 0, 0, 0, 0, null);
            }
            boolean moreRaw = false;
            boolean moreQuality = false;
            for (int cycle = 0; cycle < maxCycles; cycle++) {
                int rawDeleted = requireBatchCount(
                        session.deleteRawTelemetryBatch(
                                executionId, batchSize, rawRowsDeleted,
                                qualityRowsDeleted, batchCount));
                rawRowsDeleted = Math.addExact(rawRowsDeleted, rawDeleted);
                if (rawDeleted > 0) {
                    batchCount = Math.incrementExact(batchCount);
                }
                int qualityDeleted = requireBatchCount(
                        session.deleteQualityEvidenceBatch(
                                executionId, batchSize, rawRowsDeleted,
                                qualityRowsDeleted, batchCount));
                qualityRowsDeleted = Math.addExact(qualityRowsDeleted, qualityDeleted);
                if (qualityDeleted > 0) {
                    batchCount = Math.incrementExact(batchCount);
                }
                moreRaw = rawDeleted == batchSize;
                moreQuality = qualityDeleted == batchSize;
                if (!moreRaw && !moreQuality) {
                    break;
                }
            }
            RunStatus status = moreRaw || moreQuality
                    ? RunStatus.PARTIAL : RunStatus.SUCCEEDED;
            session.completeExecution(
                    executionId, status, rawRowsDeleted, qualityRowsDeleted, batchCount, null);
            return new RunResult(
                    executionId, status, policyCount, rawRowsDeleted,
                    qualityRowsDeleted, batchCount, null);
        } catch (RuntimeException exception) {
            String error = safeError(exception);
            session.completeExecution(
                    executionId, RunStatus.FAILED, rawRowsDeleted,
                    qualityRowsDeleted, batchCount, error);
            return new RunResult(
                    executionId, RunStatus.FAILED, policyCount, rawRowsDeleted,
                    qualityRowsDeleted, batchCount, error);
        }
    }

    private int requireBatchCount(int value) {
        if (value < 0 || value > batchSize) {
            throw new IllegalStateException("数据保留仓储返回了非法批次行数");
        }
        return value;
    }

    private String safeError(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ");
        String redacted = SECRET.matcher(singleLine).replaceAll("$1=[REDACTED]");
        return redacted.length() <= 2_000 ? redacted : redacted.substring(0, 2_000);
    }

    private String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过 128");
        }
        return value.strip();
    }

    /** 数据保留单轮运行结果。 */
    public enum RunStatus {
        /** 其他实例持有数据库锁，本实例未执行。 */
        SKIPPED_LOCKED,
        /** 已扫描完当前过期数据。 */
        SUCCEEDED,
        /** 达到本轮有界批次上限，后续轮次继续。 */
        PARTIAL,
        /** 执行失败且已经写入脱敏错误。 */
        FAILED
    }

    /**
     * 单轮数据保留结果。
     *
     * @param executionId 持久执行标识，锁忙跳过时为空
     * @param status 稳定运行结果
     * @param policyCount 本轮启用策略数
     * @param rawRowsDeleted 删除的原始遥测行数
     * @param qualityRowsDeleted 删除的质量证据行数
     * @param batchCount 实际产生删除的批次数
     * @param errorSummary 失败时的脱敏错误摘要
     */
    public record RunResult(
            UUID executionId,
            RunStatus status,
            int policyCount,
            long rawRowsDeleted,
            long qualityRowsDeleted,
            int batchCount,
            String errorSummary
    ) {
        /** 创建数据库锁忙的无副作用结果。 */
        private static RunResult skippedLocked() {
            return new RunResult(null, RunStatus.SKIPPED_LOCKED, 0, 0, 0, 0, null);
        }
    }

    /** 能在同一数据库会话中尝试获得保留任务单实例锁的持久存储。 */
    public interface Store {
        /**
         * 获得锁后执行工作，锁忙时返回空。
         *
         * @param work 只能在锁生命周期内使用数据库会话的工作
         * @param <T> 运行结果类型
         * @return 锁忙为空，否则返回工作结果
         */
        <T> Optional<T> withLock(LockedWork<T> work);
    }

    /** 数据库锁生命周期内的工作函数。 */
    @FunctionalInterface
    public interface LockedWork<T> {
        /** 使用锁定会话执行工作。 */
        T execute(Session session);
    }

    /** 只允许在持有数据库单实例锁期间使用的数据保留会话。 */
    public interface Session {
        /** 把超过失联超时的运行事实收敛为失败。 */
        int recoverStaleExecutions(Duration timeout);

        /** 统计当前启用的数据保留策略数量。 */
        int countEnabledPolicies();

        /** 创建一条运行中执行事实。 */
        void startExecution(UUID executionId, String instanceId, int enabledPolicyCount);

        /** 在同一事务中删除一批原始数值遥测并持久化提交后的累计进度。 */
        int deleteRawTelemetryBatch(
                UUID executionId,
                int limit,
                long rawRowsDeleted,
                long qualityRowsDeleted,
                int batchCount
        );

        /** 在同一事务中删除一批质量证据并持久化提交后的累计进度。 */
        int deleteQualityEvidenceBatch(
                UUID executionId,
                int limit,
                long rawRowsDeleted,
                long qualityRowsDeleted,
                int batchCount
        );

        /** 把运行中事实收敛为终态。 */
        void completeExecution(
                UUID executionId,
                RunStatus status,
                long rawRowsDeleted,
                long qualityRowsDeleted,
                int batchCount,
                String errorSummary
        );
    }
}
