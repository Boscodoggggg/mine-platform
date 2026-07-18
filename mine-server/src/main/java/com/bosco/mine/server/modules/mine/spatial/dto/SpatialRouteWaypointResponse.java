package com.bosco.mine.server.modules.mine.spatial.dto;

import java.util.UUID;

/**
 * 路线途径点顺序响应。
 *
 * @param sequenceNumber 顺序号
 * @param waypointVersionId 已发布途径点版本标识
 * @param stopRequired 是否要求停车
 * @param expectedDwellSeconds 预计停留秒数
 */
public record SpatialRouteWaypointResponse(
        int sequenceNumber,
        UUID waypointVersionId,
        boolean stopRequired,
        Integer expectedDwellSeconds
) {
}
