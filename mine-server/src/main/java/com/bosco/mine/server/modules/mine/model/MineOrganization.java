package com.bosco.mine.server.modules.mine.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 矿业客户集团或组织的持久化快照。
 *
 * @param id 组织标识
 * @param organizationCode 组织编码
 * @param name 组织名称
 * @param status 生命周期状态
 * @param createdAt 创建时间
 * @param createdBy 创建用户
 * @param updatedAt 最近更新时间
 * @param updatedBy 最近更新用户
 * @param archivedAt 归档时间
 * @param version 乐观锁版本
 */
public record MineOrganization(
        UUID id,
        String organizationCode,
        String name,
        String status,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt,
        UUID updatedBy,
        Instant archivedAt,
        long version
) {
}
