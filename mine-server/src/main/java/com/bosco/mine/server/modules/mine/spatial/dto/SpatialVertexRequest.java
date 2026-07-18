package com.bosco.mine.server.modules.mine.spatial.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 围栏顶点请求。
 *
 * @param sequenceNumber 顺序号
 * @param x 横坐标
 * @param y 纵坐标
 */
public record SpatialVertexRequest(
        @Min(1) @Max(10000) int sequenceNumber,
        double x,
        double y
) {
}
