package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改矿区请求。
 *
 * @param name 矿区名称
 * @param timezone IANA 时区名称
 * @param description 矿区说明
 * @param version 期望乐观锁版本
 */
public record MineSiteUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String timezone,
        @Size(max = 500) String description,
        @NotNull @Min(0) Long version
) {
}
