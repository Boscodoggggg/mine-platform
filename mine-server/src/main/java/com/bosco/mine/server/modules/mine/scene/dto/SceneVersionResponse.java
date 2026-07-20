package com.bosco.mine.server.modules.mine.scene.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 场景草稿或不可变发布版本及其有序图层响应。
 *
 * @param id 场景版本标识
 * @param sceneId 稳定场景标识
 * @param coordinateSystemId 坐标系标识
 * @param revisionNumber 修订号
 * @param schemaVersion 快照结构版本
 * @param coordinateMode 坐标锚点模式
 * @param originX 原点 X
 * @param originY 原点 Y
 * @param originZ 原点 Z
 * @param headingDegrees 航向角
 * @param pitchDegrees 俯仰角
 * @param rollDegrees 横滚角
 * @param scale 统一缩放
 * @param defaultCamera 默认相机
 * @param fallbackViewport 二维降级视口
 * @param status 版本状态
 * @param contentHash 发布内容摘要
 * @param publishedAt 发布时间
 * @param supersededAt 被替代时间
 * @param createdAt 创建时间
 * @param version 草稿乐观锁版本
 * @param layers 有序不可变图层
 */
public record SceneVersionResponse(
        UUID id,
        UUID sceneId,
        UUID coordinateSystemId,
        int revisionNumber,
        int schemaVersion,
        String coordinateMode,
        double originX,
        double originY,
        double originZ,
        double headingDegrees,
        double pitchDegrees,
        double rollDegrees,
        double scale,
        JsonNode defaultCamera,
        JsonNode fallbackViewport,
        String status,
        String contentHash,
        Instant publishedAt,
        Instant supersededAt,
        Instant createdAt,
        long version,
        List<SceneLayerResponse> layers
) {
    /** 冻结图层列表，避免调用方修改响应快照。 */
    public SceneVersionResponse {
        layers = List.copyOf(layers);
    }

    /**
     * 场景版本中的一项有序图层。
     *
     * @param id 图层标识
     * @param layerCode 图层编码
     * @param orderNumber 图层顺序
     * @param layerType 图层类型
     * @param assetId 静态资源标识
     * @param visible 默认是否可见
     * @param opacity 默认透明度
     * @param minimumDistance 最小显示距离
     * @param maximumDistance 最大显示距离
     * @param offsetX 偏移 X
     * @param offsetY 偏移 Y
     * @param offsetZ 偏移 Z
     * @param headingDegrees 航向角
     * @param pitchDegrees 俯仰角
     * @param rollDegrees 横滚角
     * @param scale 图层缩放
     * @param maxItems 动态对象上限
     * @param options 图层选项
     */
    public record SceneLayerResponse(
            UUID id,
            String layerCode,
            int orderNumber,
            String layerType,
            UUID assetId,
            boolean visible,
            double opacity,
            double minimumDistance,
            double maximumDistance,
            double offsetX,
            double offsetY,
            double offsetZ,
            double headingDegrees,
            double pitchDegrees,
            double rollDegrees,
            double scale,
            int maxItems,
            JsonNode options
    ) {
    }
}
