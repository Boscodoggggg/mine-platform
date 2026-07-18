package com.bosco.mine.server.modules.mine.spatial.model;

import java.util.UUID;

/**
 * 电子围栏顶点持久化快照。
 *
 * @param geofenceVersionId 围栏版本标识
 * @param sequenceNumber 顺序号
 * @param x 横坐标
 * @param y 纵坐标
 */
public record GeofenceVertexRecord(
        UUID geofenceVersionId,
        int sequenceNumber,
        double x,
        double y
) {
}
