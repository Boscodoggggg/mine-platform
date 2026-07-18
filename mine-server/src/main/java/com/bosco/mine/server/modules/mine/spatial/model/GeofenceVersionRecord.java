package com.bosco.mine.server.modules.mine.spatial.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 电子围栏版本持久化快照。
 *
 * @param id 版本标识
 * @param geofenceId 稳定围栏标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param coordinateSystemId 坐标系标识
 * @param versionNumber 业务版本号
 * @param name 版本名称
 * @param geofenceType 围栏类型
 * @param minZ 最低有效高程
 * @param maxZ 最高有效高程
 * @param boundaryToleranceMeters 边界容差米数
 * @param enterConfirmPoints 进入确认点数
 * @param exitConfirmPoints 离开确认点数
 * @param dwellSeconds 停留阈值秒数
 * @param allowedLatenessSeconds 允许迟到秒数
 * @param idleFlushSeconds 空闲刷新秒数
 * @param validFrom 有效期起始时间
 * @param validTo 有效期结束时间
 * @param status 发布状态
 * @param contentHash 内容摘要
 * @param publishedAt 发布时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record GeofenceVersionRecord(
        UUID id,
        UUID geofenceId,
        UUID organizationId,
        UUID siteId,
        UUID coordinateSystemId,
        int versionNumber,
        String name,
        String geofenceType,
        Double minZ,
        Double maxZ,
        double boundaryToleranceMeters,
        int enterConfirmPoints,
        int exitConfirmPoints,
        int dwellSeconds,
        int allowedLatenessSeconds,
        int idleFlushSeconds,
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
