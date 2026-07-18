package com.bosco.mine.server.modules.mine.spatial.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * 创建途径点草稿版本请求。
 *
 * @param coordinateSystemId 统一坐标系标识
 * @param versionNumber 业务版本号
 * @param name 版本名称
 * @param waypointType 途径点类型
 * @param x 横坐标
 * @param y 纵坐标
 * @param z 高程
 * @param radiusMeters 判定半径米数
 * @param minZ 最低有效高程
 * @param maxZ 最高有效高程
 * @param detectionPolicy 判定策略
 * @param validFrom 有效期起始时间
 * @param validTo 有效期结束时间
 */
public record WaypointVersionRequest(
        @NotNull UUID coordinateSystemId,
        @Min(1) int versionNumber,
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "LOAD|UNLOAD|CHECK|WAIT|ENTRY|EXIT") String waypointType,
        double x,
        double y,
        Double z,
        @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("10000.0") double radiusMeters,
        Double minZ,
        Double maxZ,
        @NotNull @Valid SpatialDetectionPolicy detectionPolicy,
        @NotNull Instant validFrom,
        Instant validTo
) {
}
