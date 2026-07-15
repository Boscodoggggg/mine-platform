package com.bosco.mine.server.modules.device.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 产品修改请求，产品编码创建后不可修改。
 *
 * @param name       产品名称
 * @param deviceType 设备类型
 * @param status     产品状态
 * @param version    期望版本号
 */
public record ProductUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String deviceType,
        @NotBlank @Pattern(regexp = "DRAFT|ACTIVE") String status,
        @Min(0) long version
) {
}
