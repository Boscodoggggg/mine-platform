package com.bosco.mine.server.modules.mine.scene.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 批量替换场景草稿图层的一项请求。
 *
 * @param layerCode 稳定图层编码
 * @param orderNumber 图层顺序
 * @param layerType 固定图层类型
 * @param assetId 静态图层资源标识
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
 * @param maxItems 动态对象最大数量
 * @param options 有界图层选项 JSON 对象
 */
public record SceneLayerRequest(
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,63}") String layerCode,
        @NotNull @Min(1) @Max(10000) Integer orderNumber,
        @NotBlank String layerType,
        UUID assetId,
        @NotNull Boolean visible,
        @NotNull Double opacity,
        @NotNull Double minimumDistance,
        @NotNull Double maximumDistance,
        @NotNull Double offsetX,
        @NotNull Double offsetY,
        @NotNull Double offsetZ,
        @NotNull Double headingDegrees,
        @NotNull Double pitchDegrees,
        @NotNull Double rollDegrees,
        @NotNull Double scale,
        @NotNull @Min(0) @Max(100000) Integer maxItems,
        @NotNull JsonNode options
) {
}
