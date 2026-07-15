package com.bosco.mine.server.modules.device.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设备修改请求，设备编码和所属产品创建后不可修改。
 *
 * @param name    设备名称
 * @param status  设备状态
 * @param version 期望版本号
 */
public record DeviceUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
        @Min(0) long version
) {
}
