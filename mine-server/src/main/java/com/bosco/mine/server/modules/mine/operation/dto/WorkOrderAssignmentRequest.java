package com.bosco.mine.server.modules.mine.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 向运输作业单追加车辆和可选司机分配事实的请求。
 *
 * @param vehicleId 矿区车辆标识
 * @param driverPersonId 组织级司机人员标识
 * @param validFrom 分配生效时刻
 * @param validTo 分配结束时刻
 * @param assignmentReason 分配原因
 * @param version 客户端看到的作业单当前乐观版本
 */
public record WorkOrderAssignmentRequest(
        @NotNull UUID vehicleId,
        UUID driverPersonId,
        @NotNull Instant validFrom,
        @NotNull Instant validTo,
        @NotBlank @Size(max = 500) String assignmentReason,
        @NotNull @PositiveOrZero Long version
) {
}
