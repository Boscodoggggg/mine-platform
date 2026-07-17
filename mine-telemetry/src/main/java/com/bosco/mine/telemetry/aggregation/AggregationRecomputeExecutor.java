package com.bosco.mine.telemetry.aggregation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 在常规聚合互斥锁内执行单设备、单属性和固定桶宽的显式版本化重算。
 */
public final class AggregationRecomputeExecutor {

    private static final int MAXIMUM_BUCKETS = 10_000;
    private static final Set<Long> BUCKET_SECONDS = Set.of(60L, 300L, 900L, 3_600L, 21_600L, 86_400L);
    private static final Pattern PROPERTY_ID =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|token|secret|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final AggregationExecutor.Store store;
    private final String instanceId;
    private final Clock clock;

    /**
     * 创建使用系统 UTC 时间校验请求范围的显式聚合重算执行器。
     *
     * @param store 共用常规聚合互斥锁的持久存储
     * @param instanceId 当前 Telemetry 实例标识
     */
    public AggregationRecomputeExecutor(AggregationExecutor.Store store, String instanceId) {
        this(store, instanceId, Clock.systemUTC());
    }

    /**
     * 创建可注入确定性时钟的测试执行器。
     */
    AggregationRecomputeExecutor(
            AggregationExecutor.Store store,
            String instanceId,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "显式聚合重算存储不能为空");
        this.instanceId = requireText(instanceId, "Telemetry 实例标识", 128);
        this.clock = Objects.requireNonNull(clock, "显式聚合重算时钟不能为空");
    }

    /**
     * 尝试执行或重放一个具有稳定执行标识的显式聚合重算。
     *
     * @param request 已由控制面租约保护的有界请求
     * @return 锁忙、成功或失败的稳定执行结果
     */
    public RunResult run(Request request) {
        Request validated = validate(request);
        return store.withLock(session -> runLocked(session, validated))
                .orElseGet(() -> RunResult.skippedLocked(validated));
    }

    private RunResult runLocked(AggregationExecutor.Session session, Request request) {
        Preparation preparation;
        try {
            preparation = Objects.requireNonNull(
                    session.prepareRecomputation(instanceId, request),
                    "显式聚合重算准备结果不能为空");
        } catch (RuntimeException exception) {
            return failed(request, 0, 0, safeError(exception));
        }
        if (preparation.status() == PreparationStatus.SUCCEEDED) {
            return success(request, preparation.sourceWatermark(), preparation.bucketCount());
        }
        if (preparation.status() == PreparationStatus.FAILED) {
            return failed(
                    request, preparation.sourceWatermark(), preparation.bucketCount(),
                    safeError(preparation.errorSummary()));
        }
        if (preparation.status() == PreparationStatus.REJECTED) {
            return fail(
                    session, request, preparation.sourceWatermark(),
                    preparation.bucketCount(), safeError(preparation.errorSummary()));
        }
        try {
            int written = session.recompute(request, MAXIMUM_BUCKETS);
            if (written < 0 || written > MAXIMUM_BUCKETS) {
                throw new IllegalStateException("显式聚合重算仓储返回了非法桶数");
            }
            int total = Math.addExact(preparation.bucketCount(), written);
            if (total > MAXIMUM_BUCKETS) {
                throw new IllegalStateException("显式聚合重算累计桶数超过 10000");
            }
            session.completeExecution(
                    request.executionId(), AggregationExecutor.RunStatus.SUCCEEDED, 0, null);
            return success(request, preparation.sourceWatermark(), total);
        } catch (RuntimeException exception) {
            return fail(
                    session, request, preparation.sourceWatermark(),
                    preparation.bucketCount(), safeError(exception));
        }
    }

    private RunResult fail(
            AggregationExecutor.Session session,
            Request request,
            long sourceWatermark,
            int bucketCount,
            String error
    ) {
        try {
            session.completeExecution(
                    request.executionId(), AggregationExecutor.RunStatus.FAILED, 0, error);
        } catch (RuntimeException completionFailure) {
            completionFailure.addSuppressed(new IllegalStateException(error));
            throw completionFailure;
        }
        return failed(request, sourceWatermark, bucketCount, error);
    }

    private RunResult success(Request request, long sourceWatermark, int bucketCount) {
        return new RunResult(
                request.requestId(), request.executionId(), RunStatus.SUCCEEDED,
                sourceWatermark, bucketCount, null);
    }

    private RunResult failed(
            Request request,
            long sourceWatermark,
            int bucketCount,
            String error
    ) {
        return new RunResult(
                request.requestId(), request.executionId(), RunStatus.FAILED,
                sourceWatermark, bucketCount, error);
    }

    private Request validate(Request request) {
        Objects.requireNonNull(request, "显式聚合重算请求不能为空");
        long bucketSeconds = request.bucket().toSeconds();
        if (!BUCKET_SECONDS.contains(bucketSeconds)
                || !request.bucket().equals(Duration.ofSeconds(bucketSeconds))) {
            throw new IllegalArgumentException("显式聚合重算桶宽不在固定白名单中");
        }
        requireText(request.propertyId(), "显式聚合重算属性标识", 128);
        if (!PROPERTY_ID.matcher(request.propertyId()).matches()) {
            throw new IllegalArgumentException("显式聚合重算属性标识格式非法");
        }
        if (request.from().getNano() != 0
                || request.to().getNano() != 0
                || Math.floorMod(request.from().getEpochSecond(), bucketSeconds) != 0
                || Math.floorMod(request.to().getEpochSecond(), bucketSeconds) != 0) {
            throw new IllegalArgumentException("显式聚合重算范围必须按 UTC 对齐的桶边界设置");
        }
        if (!request.to().isAfter(request.from())) {
            throw new IllegalArgumentException("显式聚合重算结束时间必须晚于开始时间");
        }
        if (request.to().isAfter(clock.instant())) {
            throw new IllegalArgumentException("显式聚合重算不得包含未来时间桶");
        }
        long bucketCount;
        try {
            bucketCount = Duration.between(request.from(), request.to()).dividedBy(request.bucket());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("显式聚合重算范围过大", exception);
        }
        if (bucketCount < 1 || bucketCount > MAXIMUM_BUCKETS) {
            throw new IllegalArgumentException("显式聚合重算范围必须包含 1 到 10000 个桶");
        }
        return request;
    }

    private static String safeError(Throwable throwable) {
        Throwable current = Objects.requireNonNull(throwable, "显式聚合重算异常不能为空");
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
        return safeError(message);
    }

    private static String safeError(String value) {
        String candidate = value == null || value.isBlank()
                ? "显式聚合重算失败" : value;
        String singleLine = candidate.replaceAll("[\\r\\n\\t]+", " ").strip();
        String redacted = SECRET.matcher(singleLine).replaceAll("$1=[REDACTED]");
        return redacted.length() <= 2_000 ? redacted : redacted.substring(0, 2_000);
    }

    private static String requireText(String value, String label, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过" + maximumLength);
        }
        return value.strip();
    }

    /**
     * 显式重算稳定结果。
     */
    public enum RunStatus {
        /** 常规或其他重算任务正在持有聚合锁。 */
        SKIPPED_LOCKED,
        /** 已提交并完成，或重放了相同执行标识的成功终态。 */
        SUCCEEDED,
        /** 已失败关闭，或重放了相同执行标识的失败终态。 */
        FAILED
    }

    /**
     * 仓储准备稳定执行事实后的状态。
     */
    public enum PreparationStatus {
        /** 已创建或恢复运行中事实，可以继续计算。 */
        READY,
        /** 相同执行标识已经成功，用于完成响应丢失后的重放。 */
        SUCCEEDED,
        /** 相同执行标识已经失败，只重放终态。 */
        FAILED,
        /** 当前策略或执行身份不允许计算，需要失败关闭运行中事实。 */
        REJECTED
    }

    /**
     * 控制面交给 Telemetry 的有界显式重算计算合同。
     *
     * @param requestId 控制面请求标识
     * @param executionId 跨租约保持稳定的执行标识
     * @param deviceId 目标设备标识
     * @param propertyId 目标数值属性
     * @param bucket 固定桶宽
     * @param from 左闭开始时间
     * @param to 右开结束时间
     */
    public record Request(
            UUID requestId,
            UUID executionId,
            UUID deviceId,
            String propertyId,
            Duration bucket,
            Instant from,
            Instant to
    ) {
        /** 校验不可为空的稳定身份和范围字段。 */
        public Request {
            Objects.requireNonNull(requestId, "显式聚合重算请求标识不能为空");
            Objects.requireNonNull(executionId, "显式聚合重算执行标识不能为空");
            Objects.requireNonNull(deviceId, "显式聚合重算设备标识不能为空");
            Objects.requireNonNull(bucket, "显式聚合重算桶宽不能为空");
            Objects.requireNonNull(from, "显式聚合重算开始时间不能为空");
            Objects.requireNonNull(to, "显式聚合重算结束时间不能为空");
        }
    }

    /**
     * 仓储在聚合锁内固定或重放的执行准备事实。
     *
     * @param status 准备状态
     * @param sourceWatermark 固定原始摄取水位
     * @param bucketCount 已提交的版本桶数
     * @param errorSummary 失败或拒绝时的原因
     */
    public record Preparation(
            PreparationStatus status,
            long sourceWatermark,
            int bucketCount,
            String errorSummary
    ) {
        /** 校验准备事实的水位、进度和错误状态一致。 */
        public Preparation {
            Objects.requireNonNull(status, "显式聚合重算准备状态不能为空");
            if (sourceWatermark < 0 || bucketCount < 0 || bucketCount > MAXIMUM_BUCKETS) {
                throw new IllegalArgumentException("显式聚合重算准备水位或桶数非法");
            }
            boolean requiresError = status == PreparationStatus.FAILED
                    || status == PreparationStatus.REJECTED;
            if (requiresError != (errorSummary != null && !errorSummary.isBlank())) {
                throw new IllegalArgumentException("显式聚合重算准备状态与错误摘要不一致");
            }
        }
    }

    /**
     * 显式重算执行或终态重放结果。
     *
     * @param requestId 控制面请求标识
     * @param executionId 稳定执行标识
     * @param status 运行结果
     * @param sourceWatermark 固定原始摄取水位
     * @param bucketCount 已提交版本桶数
     * @param errorSummary 失败时的脱敏摘要
     */
    public record RunResult(
            UUID requestId,
            UUID executionId,
            RunStatus status,
            long sourceWatermark,
            int bucketCount,
            String errorSummary
    ) {
        private static RunResult skippedLocked(Request request) {
            return new RunResult(
                    request.requestId(), request.executionId(), RunStatus.SKIPPED_LOCKED,
                    0, 0, null);
        }
    }
}
