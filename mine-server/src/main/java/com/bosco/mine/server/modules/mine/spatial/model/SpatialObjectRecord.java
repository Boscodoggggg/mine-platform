package com.bosco.mine.server.modules.mine.spatial.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 途径点、路线或电子围栏稳定对象持久化快照。
 *
 * @param id 对象标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param code 矿区内稳定编码
 * @param status 生命周期状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param archivedAt 归档时间
 * @param version 乐观锁版本
 */
public record SpatialObjectRecord(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String code,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        long version
) {
}
