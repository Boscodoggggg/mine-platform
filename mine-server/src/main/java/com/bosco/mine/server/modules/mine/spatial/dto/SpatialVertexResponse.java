package com.bosco.mine.server.modules.mine.spatial.dto;

/**
 * 围栏顶点响应。
 *
 * @param sequenceNumber 顺序号
 * @param x 横坐标
 * @param y 纵坐标
 */
public record SpatialVertexResponse(int sequenceNumber, double x, double y) {
}
