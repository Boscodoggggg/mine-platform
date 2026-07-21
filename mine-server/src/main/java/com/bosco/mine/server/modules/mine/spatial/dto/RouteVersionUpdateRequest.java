package com.bosco.mine.server.modules.mine.spatial.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 修改路线草稿版本请求。
 *
 * @param coordinateSystemId 统一坐标系标识
 * @param name 版本名称
 * @param direction 行驶方向
 * @param applicableVehicleTypes 适用车辆类型
 * @param validFrom 有效期起始时间
 * @param validTo 有效期结束时间
 * @param waypoints 有序途径点版本
 * @param version 乐观锁版本
 */
public record RouteVersionUpdateRequest(
        @NotNull UUID coordinateSystemId,
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "FORWARD|REVERSE|BIDIRECTIONAL") String direction,
        @NotEmpty @Size(max = 16) List<@NotBlank String> applicableVehicleTypes,
        @NotNull Instant validFrom,
        Instant validTo,
        @NotEmpty @Size(min = 2, max = 10000) List<@Valid SpatialRouteWaypointRequest> waypoints,
        @Min(0) long version
) {
}
