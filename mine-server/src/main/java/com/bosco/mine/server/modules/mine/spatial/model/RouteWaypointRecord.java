package com.bosco.mine.server.modules.mine.spatial.model;

import java.util.UUID;

/**
 * 路线版本途径点持久化快照。
 *
 * @param routeVersionId 路线版本标识
 * @param waypointVersionId 途径点版本标识
 * @param sequenceNumber 顺序号
 * @param stopRequired 是否要求停车
 * @param expectedDwellSeconds 预计停留秒数
 */
public record RouteWaypointRecord(
        UUID routeVersionId,
        UUID waypointVersionId,
        int sequenceNumber,
        boolean stopRequired,
        Integer expectedDwellSeconds
) {
}
