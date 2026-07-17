package com.bosco.mine.telemetry.aggregation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 每轮最多认领一个控制面租约，执行稳定重算并使用当前令牌回写终态。
 */
public final class AggregationRecomputeWorker {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|token|secret|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    private final AggregationRecomputeClient client;
    private final AggregationRecomputeExecutor executor;
    private final AggregationMetrics metrics;
    private final String instanceId;
    private final Duration leaseDuration;

    /**
     * 创建单请求、有界租约的显式聚合重算工作器。
     *
     * @param client 控制面租约客户端
     * @param executor 版本化显式重算执行器
     * @param metrics 聚合低基数指标
     * @param instanceId 当前 Telemetry 实例标识
     * @param leaseDuration 单次认领租约时长
     */
    public AggregationRecomputeWorker(
            AggregationRecomputeClient client,
            AggregationRecomputeExecutor executor,
            AggregationMetrics metrics,
            String instanceId,
            Duration leaseDuration
    ) {
        this.client = Objects.requireNonNull(client, "显式重算控制面客户端不能为空");
        this.executor = Objects.requireNonNull(executor, "显式重算执行器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "显式重算指标不能为空");
        this.metrics.recomputeEnabled();
        this.instanceId = requireText(instanceId, "Telemetry 实例标识", 128);
        if (leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMinutes(1)) < 0
                || leaseDuration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("显式重算租约时长必须在 1 分钟到 1 小时之间");
        }
        this.leaseDuration = leaseDuration;
    }

    /**
     * 认领并处理至多一个显式重算请求。
     *
     * @return 本轮稳定结果
     */
    public WorkResult runOnce() {
        metrics.recomputeStarted();
        try {
            Optional<AggregationRecomputeClient.Claim> optionalClaim =
                    client.claim(instanceId, leaseDuration);
            metrics.recomputePollSucceeded();
            if (optionalClaim.isEmpty()) {
                return record(new WorkResult(WorkStatus.NO_WORK, null, null, 0, null));
            }
            AggregationRecomputeClient.Claim claim = optionalClaim.orElseThrow();
            metrics.recomputeClaimed(claim.leaseUntil());
            AggregationRecomputeExecutor.RunResult execution;
            try {
                execution = executor.run(toRequest(claim));
            } catch (RuntimeException exception) {
                String error = safeError(exception);
                return completeFailure(claim, 0, error);
            }
            return switch (execution.status()) {
                case SKIPPED_LOCKED -> record(new WorkResult(
                        WorkStatus.SKIPPED_LOCKED, claim.requestId(), claim.executionId(),
                        execution.bucketCount(), null));
                case SUCCEEDED -> completeSuccess(claim, execution.bucketCount());
                case FAILED -> completeFailure(
                        claim, execution.bucketCount(), safeError(execution.errorSummary()));
            };
        } catch (RuntimeException exception) {
            metrics.recordRecomputeUnhandledFailure();
            throw exception;
        } finally {
            metrics.recomputeStopped();
        }
    }

    private WorkResult completeSuccess(
            AggregationRecomputeClient.Claim claim,
            int bucketCount
    ) {
        AggregationRecomputeClient.CompletionResult completed = client.complete(
                claim, AggregationRecomputeClient.CompletionStatus.SUCCEEDED, null);
        WorkStatus status = completed == AggregationRecomputeClient.CompletionResult.COMPLETED
                ? WorkStatus.SUCCEEDED : WorkStatus.LEASE_LOST;
        return record(new WorkResult(
                status, claim.requestId(), claim.executionId(), bucketCount, null));
    }

    private WorkResult completeFailure(
            AggregationRecomputeClient.Claim claim,
            int bucketCount,
            String error
    ) {
        AggregationRecomputeClient.CompletionResult completed = client.complete(
                claim, AggregationRecomputeClient.CompletionStatus.FAILED, error);
        WorkStatus status = completed == AggregationRecomputeClient.CompletionResult.COMPLETED
                ? WorkStatus.FAILED : WorkStatus.LEASE_LOST;
        return record(new WorkResult(
                status, claim.requestId(), claim.executionId(), bucketCount, error));
    }

    private WorkResult record(WorkResult result) {
        metrics.recordRecomputeResult(result);
        return result;
    }

    private AggregationRecomputeExecutor.Request toRequest(
            AggregationRecomputeClient.Claim claim
    ) {
        return new AggregationRecomputeExecutor.Request(
                claim.requestId(), claim.executionId(), claim.deviceId(),
                claim.propertyId(), claim.bucket(), claim.from(), claim.to());
    }

    private String safeError(Throwable throwable) {
        Throwable current = Objects.requireNonNull(throwable, "显式重算异常不能为空");
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
        return safeError(message);
    }

    private String safeError(String value) {
        String candidate = value == null || value.isBlank() ? "显式聚合重算失败" : value;
        String singleLine = candidate.replaceAll("[\\r\\n\\t]+", " ").strip();
        String redacted = SECRET.matcher(singleLine).replaceAll("$1=[REDACTED]");
        return redacted.length() <= 2_000 ? redacted : redacted.substring(0, 2_000);
    }

    private String requireText(String value, String label, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过" + maximumLength);
        }
        return value.strip();
    }

    /**
     * 单轮显式重算消费结果。
     */
    public enum WorkStatus {
        /** 控制面当前没有可认领请求。 */
        NO_WORK,
        /** 已成功执行并提交控制面终态。 */
        SUCCEEDED,
        /** 已失败关闭并提交控制面终态。 */
        FAILED,
        /** 聚合互斥锁忙，保留租约等待过期恢复。 */
        SKIPPED_LOCKED,
        /** 执行完成但当前控制面租约已经转移。 */
        LEASE_LOST,
        /** 控制面访问或终态回写发生未收敛基础设施异常。 */
        UNHANDLED
    }

    /**
     * 单轮消费的稳定结果和审计身份。
     *
     * @param status 工作状态
     * @param requestId 请求标识，无任务时为空
     * @param executionId 稳定执行标识，无任务时为空
     * @param bucketCount 已提交的版本化桶数
     * @param errorSummary 失败时的脱敏错误摘要
     */
    public record WorkResult(
            WorkStatus status,
            UUID requestId,
            UUID executionId,
            int bucketCount,
            String errorSummary
    ) {
        /** 校验固定状态与有界产量。 */
        public WorkResult {
            Objects.requireNonNull(status, "显式重算工作状态不能为空");
            if (bucketCount < 0 || bucketCount > 10_000) {
                throw new IllegalArgumentException("显式重算桶数必须在 0 到 10000 之间");
            }
        }
    }
}
