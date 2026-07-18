package com.bosco.mine.server.modules.mine.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 矿区坐标系响应。
 *
 * @param id 坐标系标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param systemCode 坐标系编码
 * @param name 坐标系名称
 * @param coordinateType 坐标类型
 * @param unit 坐标单位
 * @param epsgCode EPSG 编号
 * @param unified 是否为统一坐标系
 * @param minX 横轴最小边界
 * @param maxX 横轴最大边界
 * @param minY 纵轴最小边界
 * @param maxY 纵轴最大边界
 * @param minZ 高程最小边界
 * @param maxZ 高程最大边界
 * @param status 生命周期状态
 * @param archivedAt 归档时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record CoordinateSystemResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String systemCode,
        String name,
        String coordinateType,
        String unit,
        Integer epsgCode,
        boolean unified,
        double minX,
        double maxX,
        double minY,
        double maxY,
        Double minZ,
        Double maxZ,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
