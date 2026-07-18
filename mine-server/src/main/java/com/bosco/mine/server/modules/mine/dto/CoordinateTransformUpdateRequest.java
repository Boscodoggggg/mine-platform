package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 修改坐标转换草稿请求。
 *
 * @param algorithm 转换算法
 * @param parameters 算法参数
 * @param validFrom 生效起始时间
 * @param validTo 生效结束时间
 * @param version 期望乐观锁版本
 */
public record CoordinateTransformUpdateRequest(
        @NotBlank @Pattern(regexp = "IDENTITY|AFFINE_3D|WGS84_ENU") String algorithm,
        @NotNull JsonNode parameters,
        @NotNull Instant validFrom,
        Instant validTo,
        @NotNull @Min(0) Long version
) {
}
