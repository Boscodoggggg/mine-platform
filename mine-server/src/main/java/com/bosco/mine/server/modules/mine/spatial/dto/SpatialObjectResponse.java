package com.bosco.mine.server.modules.mine.spatial.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 空间稳定对象响应。
 *
 * @param id 对象标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param code 矿区内稳定编码
 * @param status 生命周期状态
 * @param archivedAt 归档时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record SpatialObjectResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String code,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
