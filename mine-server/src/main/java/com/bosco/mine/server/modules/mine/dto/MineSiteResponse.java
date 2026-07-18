package com.bosco.mine.server.modules.mine.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 矿区档案响应。
 *
 * @param id 矿区标识
 * @param organizationId 所属组织标识
 * @param siteCode 矿区编码
 * @param name 矿区名称
 * @param timezone IANA 时区
 * @param description 矿区说明
 * @param status 状态
 * @param archivedAt 归档时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param version 乐观锁版本
 */
public record MineSiteResponse(
        UUID id,
        UUID organizationId,
        String siteCode,
        String name,
        String timezone,
        String description,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
