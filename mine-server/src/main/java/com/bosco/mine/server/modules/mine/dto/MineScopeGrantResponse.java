package com.bosco.mine.server.modules.mine.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户数据范围授权响应。
 *
 * @param id 授权标识
 * @param userId 用户标识
 * @param organizationId 组织标识
 * @param siteId 矿区标识，为空表示组织级授权
 * @param grantedAt 授权时间
 * @param grantedBy 授权操作人
 */
public record MineScopeGrantResponse(
        UUID id,
        UUID userId,
        UUID organizationId,
        UUID siteId,
        Instant grantedAt,
        UUID grantedBy
) {
}
