package com.bosco.mine.server.modules.mine.spatial.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 创建空间稳定对象请求。
 *
 * @param code 矿区内稳定编码
 */
public record SpatialObjectRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]{1,63}") String code
) {
}
