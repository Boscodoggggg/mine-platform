package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 创建坐标转换草稿请求。
 *
 * @param sourceCoordinateSystemId 源坐标系标识
 * @param targetCoordinateSystemId 目标坐标系标识
 * @param versionNumber 业务版本号
 * @param algorithm 转换算法
 * @param parameters 算法参数
 * @param validFrom 生效起始时间
 * @param validTo 生效结束时间
 */
public record CoordinateTransformRequest(
        @NotNull UUID sourceCoordinateSystemId,
        @NotNull UUID targetCoordinateSystemId,
        @Min(1) int versionNumber,
        @NotBlank @Pattern(regexp = "IDENTITY|AFFINE_3D|WGS84_ENU") String algorithm,
        @NotNull JsonNode parameters,
        @NotNull Instant validFrom,
        Instant validTo
) {
}
