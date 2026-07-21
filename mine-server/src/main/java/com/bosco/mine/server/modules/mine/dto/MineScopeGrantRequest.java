package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 新增或撤销用户数据范围授权请求。
 *
 * @param userId 目标用户标识
 * @param organizationId 目标组织标识
 * @param siteId 目标矿区标识，为空表示组织级授权
 */
public record MineScopeGrantRequest(
        @NotNull UUID userId,
        @NotNull UUID organizationId,
        UUID siteId
) {
}
