package com.bosco.mine.server.modules.mine.operation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 作业单发布或生命周期动作请求。
 *
 * @param version 客户端看到的当前乐观版本
 * @param routeVersionId 发布时使用的路线版本标识
 * @param loadGeofenceVersionId 发布时使用的装载区域版本标识
 * @param unloadGeofenceVersionId 发布时使用的卸载区域版本标识
 * @param reason 取消或异常终止原因
 */
public record WorkOrderActionRequest(
        @NotNull @PositiveOrZero Long version,
        UUID routeVersionId,
        UUID loadGeofenceVersionId,
        UUID unloadGeofenceVersionId,
        @Size(max = 500) String reason
) {
}
