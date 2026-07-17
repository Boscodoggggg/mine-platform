package com.bosco.mine.telemetry.aggregation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Telemetry 认领显式聚合重算租约并使用当前令牌回写终态的内部控制面合同。
 */
public interface AggregationRecomputeClient {

    /**
     * 原子认领一个待处理或租约过期的显式重算请求。
     *
     * @param instanceId 当前 Telemetry 实例标识
     * @param leaseDuration 请求的有界租约时长
     * @return 当前租约，无任务时为空
     */
    Optional<Claim> claim(String instanceId, Duration leaseDuration);

    /**
     * 使用当前租约令牌把请求收敛为成功或失败。
     *
     * @param claim 当前租约
     * @param status 终态
     * @param error 失败时的脱敏错误摘要
     * @return 完成成功或租约已经丢失
     */
    CompletionResult complete(Claim claim, CompletionStatus status, String error);

    /**
     * 控制面允许回写的终态。
     */
    enum CompletionStatus {
        /** 显式重算执行成功。 */
        SUCCEEDED,
        /** 显式重算执行失败。 */
        FAILED
    }

    /**
     * 当前租约完成请求的结果。
     */
    enum CompletionResult {
        /** 当前令牌成功提交终态。 */
        COMPLETED,
        /** 租约已经转移或请求已由其他回调完成。 */
        LEASE_LOST
    }

    /**
     * 控制面交付的显式聚合重算当前租约。
     *
     * @param requestId 请求标识
     * @param executionId 跨租约稳定的执行标识
     * @param claimToken 当前租约随机令牌
     * @param deviceId 目标设备标识
     * @param propertyId 目标数值属性
     * @param bucket 固定桶宽
     * @param from 左闭开始时间
     * @param to 右开结束时间
     * @param leaseUntil 当前租约到期时间
     * @param attemptCount 累计认领次数
     */
    record Claim(
            UUID requestId,
            UUID executionId,
            UUID claimToken,
            UUID deviceId,
            String propertyId,
            Duration bucket,
            Instant from,
            Instant to,
            Instant leaseUntil,
            int attemptCount
    ) {
        private static final Set<Duration> BUCKETS = Set.of(
                Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofHours(1), Duration.ofHours(6), Duration.ofDays(1));
        private static final Pattern PROPERTY =
                Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");

        /** 校验租约计算合同只包含完整稳定身份和白名单桶宽。 */
        public Claim {
            Objects.requireNonNull(requestId, "显式重算请求标识不能为空");
            Objects.requireNonNull(executionId, "显式重算执行标识不能为空");
            Objects.requireNonNull(claimToken, "显式重算租约令牌不能为空");
            Objects.requireNonNull(deviceId, "显式重算设备标识不能为空");
            if (propertyId == null || !PROPERTY.matcher(propertyId).matches()) {
                throw new IllegalArgumentException("显式重算属性标识格式非法");
            }
            if (bucket == null || !BUCKETS.contains(bucket)) {
                throw new IllegalArgumentException("显式重算桶宽不在固定白名单中");
            }
            Objects.requireNonNull(from, "显式重算开始时间不能为空");
            Objects.requireNonNull(to, "显式重算结束时间不能为空");
            Objects.requireNonNull(leaseUntil, "显式重算租约到期时间不能为空");
            if (!to.isAfter(from) || attemptCount < 1) {
                throw new IllegalArgumentException("显式重算范围或认领次数非法");
            }
        }
    }
}
