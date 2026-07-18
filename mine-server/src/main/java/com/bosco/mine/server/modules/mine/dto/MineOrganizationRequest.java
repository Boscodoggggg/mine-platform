package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建组织请求。
 *
 * @param organizationCode 组织业务编码
 * @param name 组织名称
 */
public record MineOrganizationRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]{1,63}")
        String organizationCode,
        @NotBlank @Size(max = 128) String name
) {
}
