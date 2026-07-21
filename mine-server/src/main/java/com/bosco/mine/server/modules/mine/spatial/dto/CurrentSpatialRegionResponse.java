package com.bosco.mine.server.modules.mine.spatial.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 当前矿区已发布且处于有效期内的空间区域快照。
 *
 * @param siteId 矿区标识
 * @param evaluatedAt 快照评估时刻
 * @param nextChangeAt 已发布配置的下一次有效期边界
 * @param items 当前生效区域
 */
public record CurrentSpatialRegionResponse(
        UUID siteId,
        Instant evaluatedAt,
        Instant nextChangeAt,
        List<Item> items
) {

    /** 创建不可变空间区域快照。 */
    public CurrentSpatialRegionResponse {
        items = List.copyOf(items);
    }

    /**
     * 当前生效的途径点或电子围栏区域。
     *
     * @param zoneType 区域类型
     * @param zoneId 稳定区域对象标识
     * @param zoneVersionId 生效版本标识
     * @param coordinateSystemId 统一坐标系标识
     * @param name 区域名称
     * @param contentHash 发布内容哈希
     * @param validFrom 生效时间
     * @param validTo 失效时间
     * @param shape 几何形状
     * @param x 圆心横坐标
     * @param y 圆心纵坐标
     * @param radiusMeters 圆形半径，单位米
     * @param vertices 多边形有序顶点
     */
    public record Item(
            String zoneType,
            UUID zoneId,
            UUID zoneVersionId,
            UUID coordinateSystemId,
            String name,
            String contentHash,
            Instant validFrom,
            Instant validTo,
            String shape,
            Double x,
            Double y,
            Double radiusMeters,
            List<SpatialVertexResponse> vertices
    ) {

        /** 创建不可变空间区域条目。 */
        public Item {
            vertices = List.copyOf(vertices);
        }
    }
}
