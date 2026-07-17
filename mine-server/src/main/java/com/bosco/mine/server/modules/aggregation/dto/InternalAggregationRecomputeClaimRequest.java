package com.bosco.mine.server.modules.aggregation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;

/**
 * Telemetry 请求认领一个显式聚合重算任务。
 *
 * @param instanceId Telemetry 实例标识
 * @param leaseDuration 请求的有界租约时长
 */
public record InternalAggregationRecomputeClaimRequest(
        @NotBlank @Size(max = 128) String instanceId,
        @NotNull Duration leaseDuration
) {
}
