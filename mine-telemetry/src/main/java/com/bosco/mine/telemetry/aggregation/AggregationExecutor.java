package com.bosco.mine.telemetry.aggregation;

import com.bosco.mine.common.id.IdGenerator;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 在数据库单实例锁内编排固定源水位和有界批次的版本化数值聚合。
 */
public final class AggregationExecutor {

    private static final int MAXIMUM_BATCH_SIZE = 10_000;
    private static final int MAXIMUM_CYCLES = 1_000;
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
     * 创建使用 UUIDv7 执行标识的数值聚合执行器。
     *
     * @param store 数值聚合持久存储
     * @param instanceId 当前 Telemetry 实例标识
     * @param batchSize 单批最大聚合桶数
     * @param maxCycles 单轮最大批次周期数
     * @param staleExecutionTimeout 运行事实失联超时
     */
    public AggregationExecutor(
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

    /**
     * 创建可注入确定性执行标识的测试执行器。
     */
    AggregationExecutor(
            Store store,
            String instanceId,
            int batchSize,
            int maxCycles,
            Duration staleExecutionTimeout,
            Supplier<UUID> executionIds
    ) {
        this.store = Objects.requireNonNull(store, "数值聚合存储不能为空");
        this.instanceId = requireText(instanceId, "数值聚合实例标识");
        if (batchSize < 1 || batchSize > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("数值聚合批次大小必须在 1 到 10000 之间");
        }
        if (maxCycles < 1 || maxCycles > MAXIMUM_CYCLES) {
            throw new IllegalArgumentException("数值聚合单轮周期必须在 1 到 1000 之间");
        }
        if (staleExecutionTimeout == null
                || staleExecutionTimeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("数值聚合失联超时必须至少一秒");
        }
        this.batchSize = batchSize;
        this.maxCycles = maxCycles;
        this.staleExecutionTimeout = staleExecutionTimeout;
        this.executionIds = Objects.requireNonNull(executionIds, "数值聚合执行标识生成器不能为空");
    }

    /**
     * 尝试执行一轮有界数值聚合任务。
     *
     * @return 锁忙跳过、成功、部分完成或失败的稳定结果
     */
    public RunResult runOnce() {
        return store.withLock(this::runLocked).orElseGet(RunResult::skippedLocked);
    }

    /**
     * 在持锁会话中固定计划并收敛执行事实。
     */
    private RunResult runLocked(Session session) {
        session.recoverStaleExecutions(staleExecutionTimeout);
        Plan plan = Objects.requireNonNull(session.loadPlan(), "数值聚合计划不能为空");
        UUID executionId = Objects.requireNonNull(
                executionIds.get(), "数值聚合执行标识生成器不得返回空值");
        session.startExecution(executionId, instanceId, plan);
        int bucketCount = 0;
        long lateRowsIgnored = 0;
        try {
            if (plan.policyCount() == 0) {
                session.completeExecution(executionId, RunStatus.SUCCEEDED, 0, null);
                return new RunResult(
                        executionId, RunStatus.SUCCEEDED, 0, 0,
                        plan.sourceWatermark(), 0, 0, null);
            }
            lateRowsIgnored = requireLateRows(
                    session.countIgnoredLateRows(executionId, plan));
            boolean more = false;
            for (int cycle = 0; cycle < maxCycles; cycle++) {
                int written = requireBatchCount(
                        session.aggregateNextBatch(executionId, batchSize));
                bucketCount = Math.addExact(bucketCount, written);
                more = written == batchSize;
                if (!more) {
                    break;
                }
            }
            RunStatus status = more ? RunStatus.PARTIAL : RunStatus.SUCCEEDED;
            session.completeExecution(executionId, status, lateRowsIgnored, null);
            return new RunResult(
                    executionId, status, plan.policyCount(), plan.ruleCount(),
                    plan.sourceWatermark(), bucketCount, lateRowsIgnored, null);
        } catch (RuntimeException exception) {
            String error = safeError(exception);
            session.completeExecution(
                    executionId, RunStatus.FAILED, lateRowsIgnored, error);
            return new RunResult(
                    executionId, RunStatus.FAILED, plan.policyCount(), plan.ruleCount(),
                    plan.sourceWatermark(), bucketCount, lateRowsIgnored, error);
        }
    }

    /**
     * 拒绝仓储突破执行器配置的单批边界。
     */
    private int requireBatchCount(int value) {
        if (value < 0 || value > batchSize) {
            throw new IllegalStateException("数值聚合仓储返回了非法批次桶数");
        }
        return value;
    }

    /**
     * 拒绝仓储返回负数迟到计数。
     */
    private long requireLateRows(long value) {
        if (value < 0) {
            throw new IllegalStateException("数值聚合仓储返回了非法超窗口迟到行数");
        }
        return value;
    }

    /**
     * 生成单行、脱敏且长度受控的失败摘要。
     */
    private String safeError(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ");
        String redacted = SECRET.matcher(singleLine).replaceAll("$1=[REDACTED]");
        return redacted.length() <= 2_000 ? redacted : redacted.substring(0, 2_000);
    }

    /**
     * 校验并清理实例标识。
     */
    private String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过 128");
        }
        return value.strip();
    }

    /**
     * 数值聚合单轮稳定结果。
     */
    public enum RunStatus {
        /** 其他实例持有数据库锁，本实例未执行。 */
        SKIPPED_LOCKED,
        /** 已扫描完固定源水位下的全部闭合候选桶。 */
        SUCCEEDED,
        /** 达到本轮有界上限，后续轮次继续。 */
        PARTIAL,
        /** 执行失败且已经写入脱敏错误。 */
        FAILED
    }

    /**
     * 锁内固定的聚合执行计划。
     *
     * @param sourceWatermark 本轮可见的最大原始遥测摄取水位
     * @param previousSuccessWatermark 上次完整成功扫描的源水位
     * @param policyCount 本轮启用设备策略数
     * @param ruleCount 本轮启用属性桶规则数
     */
    public record Plan(
            long sourceWatermark,
            long previousSuccessWatermark,
            int policyCount,
            int ruleCount
    ) {
        /** 校验水位和计划计数只能单向且非负。 */
        public Plan {
            if (sourceWatermark < 0
                    || previousSuccessWatermark < 0
                    || previousSuccessWatermark > sourceWatermark
                    || policyCount < 0
                    || ruleCount < 0
                    || (policyCount == 0 && ruleCount != 0)
                    || (policyCount > 0 && ruleCount < policyCount)) {
                throw new IllegalArgumentException("数值聚合执行计划水位或计数非法");
            }
        }
    }

    /**
     * 单轮数值聚合结果。
     *
     * @param executionId 持久执行标识，锁忙跳过时为空
     * @param status 稳定运行结果
     * @param policyCount 启用设备策略数
     * @param ruleCount 启用属性桶规则数
     * @param sourceWatermark 固定源摄取水位
     * @param bucketCount 已提交聚合版本桶数
     * @param lateRowsIgnored 超出修正窗口的迟到原始遥测行数
     * @param errorSummary 失败时的脱敏摘要
     */
    public record RunResult(
            UUID executionId,
            RunStatus status,
            int policyCount,
            int ruleCount,
            long sourceWatermark,
            int bucketCount,
            long lateRowsIgnored,
            String errorSummary
    ) {
        /** 创建数据库锁忙的无副作用结果。 */
        private static RunResult skippedLocked() {
            return new RunResult(
                    null, RunStatus.SKIPPED_LOCKED, 0, 0, 0, 0, 0, null);
        }
    }

    /**
     * 能在同一数据库会话中尝试获得聚合单实例锁的持久存储。
     */
    public interface Store {
        /**
         * 获得锁后执行工作，锁忙时返回空。
         */
        <T> Optional<T> withLock(LockedWork<T> work);
    }

    /**
     * 数据库聚合锁生命周期内的工作函数。
     *
     * @param <T> 工作结果类型
     */
    @FunctionalInterface
    public interface LockedWork<T> {
        /** 使用锁定会话执行工作。 */
        T execute(Session session);
    }

    /**
     * 只允许在持有数据库聚合单实例锁期间使用的会话。
     */
    public interface Session {
        /** 把超过失联超时的运行事实收敛为失败。 */
        int recoverStaleExecutions(Duration timeout);

        /** 固定源水位、上次成功水位和启用策略计数。 */
        Plan loadPlan();

        /** 创建一条运行中聚合执行事实。 */
        void startExecution(UUID executionId, String instanceId, Plan executionPlan);

        /** 计算并原子提交下一批尚未由本轮处理的闭合聚合桶。 */
        int aggregateNextBatch(UUID executionId, int limit);

        /** 原子追加调用方已经计算并校验的一批版本化聚合事实。 */
        int writeRollupBatch(
                UUID executionId,
                List<AggregationExecutionRepository.RollupWrite> rollups
        );

        /** 统计上次完整成功后新出现的超窗口迟到原始遥测行。 */
        long countIgnoredLateRows(UUID executionId, Plan executionPlan);

        /** 把运行中事实收敛为不可变终态。 */
        void completeExecution(
                UUID executionId,
                RunStatus status,
                long lateRowsIgnored,
                String errorSummary
        );
    }
}
