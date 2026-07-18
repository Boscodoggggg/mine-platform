package com.bosco.mine.server.modules.mine.spatial.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 路线版本响应。
 *
 * @param id 版本标识
 * @param routeId 稳定路线标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param coordinateSystemId 统一坐标系标识
 * @param versionNumber 业务版本号
 * @param name 版本名称
 * @param direction 行驶方向
 * @param applicableVehicleTypes 适用车辆类型
 * @param validFrom 有效期起始时间
 * @param validTo 有效期结束时间
 * @param status 发布状态
 * @param contentHash 发布内容摘要
 * @param publishedAt 发布时间
 * @param waypoints 有序途径点版本
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record RouteVersionResponse(
        UUID id,
        UUID routeId,
        UUID organizationId,
        UUID siteId,
        UUID coordinateSystemId,
        int versionNumber,
        String name,
        String direction,
        List<String> applicableVehicleTypes,
        Instant validFrom,
        Instant validTo,
        String status,
        String contentHash,
        Instant publishedAt,
        List<SpatialRouteWaypointResponse> waypoints,
        Instant createdAt,
        Instant updatedAt,
        long version
) {

    /** 创建不可变路线响应。 */
    public RouteVersionResponse {
        applicableVehicleTypes = List.copyOf(applicableVehicleTypes);
        waypoints = List.copyOf(waypoints);
    }
}
