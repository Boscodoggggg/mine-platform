package com.bosco.mine.server.modules.aggregation.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 单轮数值聚合执行历史响应。
 */
public record AggregationExecutionResponse(
        UUID executionId,
        String instanceId,
        String status,
        int policyCount,
        int ruleCount,
        long sourceWatermark,
        int bucketCount,
        Long rollupWatermark,
        long lateRowsIgnored,
        Instant startedAt,
        Instant completedAt,
        String lastError
) {
}
