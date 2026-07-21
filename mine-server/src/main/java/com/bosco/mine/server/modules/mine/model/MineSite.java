package com.bosco.mine.server.modules.mine.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 组织下属矿区的持久化快照。
 *
 * @param id 矿区标识
 * @param organizationId 所属组织标识
 * @param siteCode 矿区编码
 * @param name 矿区名称
 * @param timezone IANA 时区名称
 * @param description 矿区说明
 * @param status 生命周期状态
 * @param createdAt 创建时间
 * @param createdBy 创建用户
 * @param updatedAt 最近更新时间
 * @param updatedBy 最近更新用户
 * @param archivedAt 归档时间
 * @param version 乐观锁版本
 */
public record MineSite(
        UUID id,
        UUID organizationId,
        String siteCode,
        String name,
        String timezone,
        String description,
        String status,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt,
        UUID updatedBy,
        Instant archivedAt,
        long version
) {
}
