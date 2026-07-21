package com.bosco.mine.server.modules.mine.spatial.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 显式创建空间历史重放任务的请求。
 *
 * @param zoneType 区域类型
 * @param zoneVersionId 不可变发布区域版本标识
 * @param from 重放开始时间，包含
 * @param to 重放结束时间，不包含
 * @param reason 管理员填写的重放原因
 */
public record SpatialReplayRequest(
        @NotBlank @Pattern(regexp = "WAYPOINT|GEOFENCE") String zoneType,
        @NotNull UUID zoneVersionId,
        @NotNull Instant from,
        @NotNull Instant to,
        @NotBlank @Size(max = 1000) String reason
) {
}
