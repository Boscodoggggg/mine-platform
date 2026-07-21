package com.bosco.mine.server.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 产品创建请求。
 *
 * @param productKey 稳定产品编码
 * @param name       产品名称
 * @param deviceType 设备类型
 * @param status     初始状态
 */
public record ProductCreateRequest(
        @NotBlank
        @Pattern(regexp = "[a-z][a-z0-9-]{2,63}") String productKey,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String deviceType,
        @NotBlank @Pattern(regexp = "DRAFT|ACTIVE") String status
) {
}
