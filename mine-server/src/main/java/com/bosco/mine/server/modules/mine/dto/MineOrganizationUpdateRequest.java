package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改组织请求。
 *
 * @param name 组织名称
 * @param version 期望乐观锁版本
 */
public record MineOrganizationUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull @Min(0) Long version
) {
}
