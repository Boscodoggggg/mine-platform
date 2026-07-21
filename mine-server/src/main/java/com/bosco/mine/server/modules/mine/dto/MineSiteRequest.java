package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建矿区请求。
 *
 * @param siteCode 矿区业务编码
 * @param name 矿区名称
 * @param timezone IANA 时区名称
 * @param description 矿区说明
 */
public record MineSiteRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]{1,63}")
        String siteCode,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String timezone,
        @Size(max = 500) String description
) {
}
