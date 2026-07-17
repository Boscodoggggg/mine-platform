package com.bosco.mine.server.modules.aggregation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 创建显式聚合重算请求。
 *
 * @param deviceId 设备标识
 * @param propertyId 数值属性标识
 * @param bucket 固定白名单桶宽
 * @param from 重算开始时间，包含
 * @param to 重算结束时间，不包含
 * @param reason 人工重算原因
 */
public record AggregationRecomputeRequest(
        @NotNull UUID deviceId,
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,127}") String propertyId,
        @NotNull Duration bucket,
        @NotNull Instant from,
        @NotNull Instant to,
        @NotBlank @Size(max = 500) String reason
) {
}
