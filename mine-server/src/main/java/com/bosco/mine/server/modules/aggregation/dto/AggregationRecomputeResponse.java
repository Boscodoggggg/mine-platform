package com.bosco.mine.server.modules.aggregation.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 聚合重算请求事实响应。
 *
 * @param requestId 请求标识
 * @param deviceId 设备标识
 * @param propertyId 属性标识
 * @param bucket 桶宽
 * @param from 开始时间
 * @param to 结束时间
 * @param reason 重算原因
 * @param idempotencyKey 幂等键
 * @param status 请求状态
 * @param requestedBy 申请人
 * @param createdAt 创建时间
 * @param completedAt 完成时间
 * @param executionId Telemetry 执行标识
 * @param lastError 脱敏错误摘要
 */
public record AggregationRecomputeResponse(
        UUID requestId,
        UUID deviceId,
        String propertyId,
        Duration bucket,
        Instant from,
        Instant to,
        String reason,
        String idempotencyKey,
        String status,
        UUID requestedBy,
        Instant createdAt,
        Instant completedAt,
        UUID executionId,
        String lastError
) {
}
