package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 矿区资产换绑预览与执行请求。
 *
 * @param bindingType 拟建立的明确绑定类型
 * @param resourceId 被移动的设备、标签或人员标识
 * @param targetId 拟绑定的车辆或人员标识
 * @param role 主辅设备或主替班司机角色，标签关系为空
 * @param effectiveAt 换绑生效时刻
 * @param reason 换绑业务原因
 */
public record BindingSwitchRequest(
        @NotBlank
        @Pattern(regexp = "VEHICLE_DEVICE|VEHICLE_TAG|PERSON_TAG|PERSON_VEHICLE")
        String bindingType,
        @NotNull UUID resourceId,
        @NotNull UUID targetId,
        @Size(max = 24) String role,
        @NotNull Instant effectiveAt,
        @NotBlank @Size(max = 500) String reason
) {
}
