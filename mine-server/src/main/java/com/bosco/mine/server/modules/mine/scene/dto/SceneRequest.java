package com.bosco.mine.server.modules.mine.scene.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建或乐观修改稳定场景的请求。
 *
 * @param sceneCode 矿区内稳定场景编码
 * @param name 场景名称
 * @param description 可选场景说明
 * @param version 修改时的乐观版本，创建时固定为零
 */
public record SceneRequest(
        @NotBlank
        @Pattern(regexp = "[a-z][a-z0-9-]{1,63}")
        String sceneCode,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @Min(0) long version
) {
}
