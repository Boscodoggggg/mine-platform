package com.bosco.mine.server.modules.mine.spatial.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 创建电子围栏草稿版本请求。
 *
 * @param coordinateSystemId 统一坐标系标识
 * @param versionNumber 业务版本号
 * @param name 版本名称
 * @param geofenceType 围栏类型
 * @param minZ 最低有效高程
 * @param maxZ 最高有效高程
 * @param detectionPolicy 判定策略
 * @param validFrom 有效期起始时间
 * @param validTo 有效期结束时间
 * @param vertices 有序顶点
 */
public record GeofenceVersionRequest(
        @NotNull UUID coordinateSystemId,
        @Min(1) int versionNumber,
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "RESTRICTED|WORK|LOAD|UNLOAD|WAIT|SAFETY|OTHER") String geofenceType,
        Double minZ,
        Double maxZ,
        @NotNull @Valid SpatialDetectionPolicy detectionPolicy,
        @NotNull Instant validFrom,
        Instant validTo,
        @NotNull @Size(min = 3, max = 10000) List<@Valid SpatialVertexRequest> vertices
) {
}
