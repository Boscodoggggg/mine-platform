package com.bosco.mine.server.modules.aggregation.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 交给 Telemetry 的显式聚合重算租约。
 *
 * @param requestId 请求标识
 * @param executionId 跨租约稳定的执行标识
 * @param claimToken 当前租约令牌
 * @param deviceId 目标设备标识
 * @param propertyId 目标数值属性
 * @param bucket 固定桶宽
 * @param from 左闭开始时间
 * @param to 右开结束时间
 * @param leaseUntil 租约到期时间
 * @param attemptCount 累计认领次数
 */
public record InternalAggregationRecomputeClaim(
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
}
