package com.bosco.mine.server.modules.mine.spatial.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 路线途径点顺序请求。
 *
 * @param sequenceNumber 顺序号
 * @param waypointVersionId 已发布途径点版本标识
 * @param stopRequired 是否要求停车
 * @param expectedDwellSeconds 预计停留秒数
 */
public record SpatialRouteWaypointRequest(
        @Min(1) @Max(10000) int sequenceNumber,
        @NotNull UUID waypointVersionId,
        boolean stopRequired,
        @Min(0) @Max(86400) Integer expectedDwellSeconds
) {
}
