package com.bosco.mine.server.modules.mine.scene.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 创建或乐观修改场景草稿坐标和视图配置的请求。
 *
 * @param coordinateSystemId 矿区统一坐标系标识
 * @param coordinateMode 坐标锚点模式
 * @param originX 原点 X
 * @param originY 原点 Y
 * @param originZ 原点 Z
 * @param headingDegrees 航向角
 * @param pitchDegrees 俯仰角
 * @param rollDegrees 横滚角
 * @param scale 统一缩放
 * @param defaultCamera 默认相机 JSON 对象
 * @param fallbackViewport 独立二维降级视口 JSON 对象
 * @param version 修改时的乐观版本，创建时固定为零
 */
public record SceneVersionRequest(
        @NotNull UUID coordinateSystemId,
        @NotBlank String coordinateMode,
        double originX,
        double originY,
        double originZ,
        double headingDegrees,
        double pitchDegrees,
        double rollDegrees,
        double scale,
        @NotNull JsonNode defaultCamera,
        @NotNull JsonNode fallbackViewport,
        @Min(0) long version
) {
}
