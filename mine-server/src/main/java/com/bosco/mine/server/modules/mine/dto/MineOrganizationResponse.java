package com.bosco.mine.server.modules.mine.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 组织档案响应。
 *
 * @param id 组织标识
 * @param organizationCode 组织编码
 * @param name 组织名称
 * @param status 状态
 * @param archivedAt 归档时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param version 乐观锁版本
 */
public record MineOrganizationResponse(
        UUID id,
        String organizationCode,
        String name,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
