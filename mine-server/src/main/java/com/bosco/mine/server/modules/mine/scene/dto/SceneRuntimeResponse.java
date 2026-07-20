package com.bosco.mine.server.modules.mine.scene.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 默认场景最后正确运行快照或结构化降级结果。
 *
 * @param siteId 矿区标识
 * @param status 运行状态，固定为 READY 或 DEGRADED
 * @param reason 降级原因，READY 时固定为 NONE
 * @param snapshot 完整运行快照，降级时必须为空
 */
public record SceneRuntimeResponse(
        UUID siteId,
        String status,
        String reason,
        RuntimeSnapshot snapshot
) {

    /**
     * 已通过完整性校验的场景运行快照。
     *
     * @param sceneId 稳定场景标识
     * @param sceneCode 稳定场景编码
     * @param sceneName 场景名称
     * @param versionId 不可变发布版本标识
     * @param revisionNumber 修订号
     * @param schemaVersion 快照结构版本
     * @param contentHash 发布内容摘要
     * @param coordinateSystemId 统一坐标系标识
     * @param coordinateMode 坐标锚点模式
     * @param originX 场景原点 X
     * @param originY 场景原点 Y
     * @param originZ 场景原点 Z
     * @param headingDegrees 航向角
     * @param pitchDegrees 俯仰角
     * @param rollDegrees 横滚角
     * @param scale 统一缩放
     * @param defaultCamera 默认相机配置
     * @param fallbackViewport 二维降级视口
     * @param publishedAt 发布时间
     * @param layerCount 图层数量
     * @param layers 有序图层
     */
    public record RuntimeSnapshot(
            UUID sceneId,
            String sceneCode,
            String sceneName,
            UUID versionId,
            int revisionNumber,
            int schemaVersion,
            String contentHash,
            UUID coordinateSystemId,
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
            Instant publishedAt,
            int layerCount,
            List<RuntimeLayer> layers
    ) {
        /** 冻结图层列表并校验数量，禁止返回可变或半包快照。 */
        public RuntimeSnapshot {
            layers = List.copyOf(Objects.requireNonNull(layers, "场景运行图层不能为空"));
            if (layerCount != layers.size()) {
                throw new IllegalArgumentException("场景运行图层数量不一致");
            }
        }
    }

    /**
     * 运行快照中的一项有序图层。
     *
     * @param id 图层标识
     * @param layerCode 图层编码
     * @param orderNumber 图层顺序
     * @param layerType 图层类型
     * @param visible 默认是否可见
     * @param opacity 默认透明度
     * @param minimumDistance 最小显示距离
     * @param maximumDistance 最大显示距离
     * @param offsetX 局部偏移 X
     * @param offsetY 局部偏移 Y
     * @param offsetZ 局部偏移 Z
     * @param headingDegrees 航向角
     * @param pitchDegrees 俯仰角
     * @param rollDegrees 横滚角
     * @param scale 图层缩放
     * @param maxItems 动态对象上限
     * @param options 有界图层选项
     * @param asset 可选静态资源入口
     */
    public record RuntimeLayer(
            UUID id,
            String layerCode,
            int orderNumber,
            String layerType,
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
            JsonNode options,
            RuntimeAsset asset
    ) {
    }

    /**
     * 受权同源静态资源入口。
     *
     * @param assetId 资源标识
     * @param assetType 资源类型
     * @param contentDigest 上传内容摘要
     * @param fileCount 已登记普通文件数量
     * @param entryUrl 同源受权入口 URL
     */
    public record RuntimeAsset(
            UUID assetId,
            String assetType,
            String contentDigest,
            int fileCount,
            String entryUrl
    ) {
    }
}
