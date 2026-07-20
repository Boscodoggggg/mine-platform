package com.bosco.mine.server.modules.mine.scene.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 稳定场景元数据响应。
 *
 * @param id 场景标识
 * @param organizationId 组织标识
 * @param siteId 矿区标识
 * @param sceneCode 稳定编码
 * @param name 场景名称
 * @param description 场景说明
 * @param defaultScene 是否为矿区默认场景
 * @param status 生命周期状态
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param archivedAt 归档时间
 * @param version 乐观锁版本
 */
public record SceneResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String sceneCode,
        String name,
        String description,
        boolean defaultScene,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        long version
) {
}
