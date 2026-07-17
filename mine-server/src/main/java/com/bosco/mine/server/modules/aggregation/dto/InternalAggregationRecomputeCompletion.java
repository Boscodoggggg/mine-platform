package com.bosco.mine.server.modules.aggregation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Telemetry 使用当前租约回写显式聚合重算终态。
 *
 * @param executionId 稳定执行标识
 * @param claimToken 当前租约令牌
 * @param status SUCCEEDED 或 FAILED
 * @param error 失败时的错误摘要
 */
public record InternalAggregationRecomputeCompletion(
        @NotNull UUID executionId,
        @NotNull UUID claimToken,
        @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
        @Size(max = 4000) String error
) {
}
