package com.bosco.mine.server.modules.mine.spatial.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 空间进入、离开和停留判定策略。
 *
 * @param boundaryToleranceMeters 边界容差米数
 * @param enterConfirmPoints 进入确认点数
 * @param exitConfirmPoints 离开确认点数
 * @param dwellSeconds 停留事件阈值秒数
 * @param allowedLatenessSeconds 允许迟到秒数
 * @param idleFlushSeconds 空闲水位刷新秒数
 */
public record SpatialDetectionPolicy(
        @DecimalMin("0.0") @DecimalMax("1000.0") double boundaryToleranceMeters,
        @Min(1) @Max(20) int enterConfirmPoints,
        @Min(1) @Max(20) int exitConfirmPoints,
        @Min(0) @Max(86400) int dwellSeconds,
        @Min(0) @Max(300) int allowedLatenessSeconds,
        @Min(1) @Max(3600) int idleFlushSeconds
) {
}
