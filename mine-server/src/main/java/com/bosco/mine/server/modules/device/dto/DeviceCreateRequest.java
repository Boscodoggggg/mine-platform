package com.bosco.mine.server.modules.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 设备创建请求。
 *
 * @param productId 所属产品标识
 * @param deviceCode 矿区内唯一设备编码
 * @param name       设备名称
 * @param status     初始状态
 */
public record DeviceCreateRequest(
        @NotNull UUID productId,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{1,127}") String deviceCode,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status
) {
}
