package com.bosco.mine.server.modules.mine.spatial.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 途径点版本响应。
 *
 * @param id 版本标识
 * @param waypointId 稳定途径点标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param coordinateSystemId 统一坐标系标识
 * @param versionNumber 业务版本号
 * @param name 版本名称
 * @param waypointType 途径点类型
 * @param x 横坐标
 * @param y 纵坐标
 * @param z 高程
 * @param radiusMeters 判定半径米数
 * @param minZ 最低有效高程
 * @param maxZ 最高有效高程
 * @param detectionPolicy 判定策略
 * @param validFrom 有效期起始时间
 * @param validTo 有效期结束时间
 * @param status 发布状态
 * @param contentHash 发布内容摘要
 * @param publishedAt 发布时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record WaypointVersionResponse(
        UUID id,
        UUID waypointId,
        UUID organizationId,
        UUID siteId,
        UUID coordinateSystemId,
        int versionNumber,
        String name,
        String waypointType,
        double x,
        double y,
        Double z,
        double radiusMeters,
        Double minZ,
        Double maxZ,
        SpatialDetectionPolicy detectionPolicy,
        Instant validFrom,
        Instant validTo,
        String status,
        String contentHash,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
