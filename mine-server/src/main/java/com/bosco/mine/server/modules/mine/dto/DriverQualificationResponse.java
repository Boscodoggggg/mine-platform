package com.bosco.mine.server.modules.mine.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 司机车型资质响应。
 *
 * @param id 资质标识
 * @param personId 人员标识
 * @param qualificationCode 资质编码
 * @param name 资质名称
 * @param allowedVehicleTypes 允许驾驶的车辆类型
 * @param validFrom 有效开始日期
 * @param validTo 有效结束日期
 * @param status 资质状态
 * @param revokedAt 吊销时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record DriverQualificationResponse(
        UUID id,
        UUID personId,
        String qualificationCode,
        String name,
        List<String> allowedVehicleTypes,
        LocalDate validFrom,
        LocalDate validTo,
        String status,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {

    /** 创建不可变车型列表。 */
    public DriverQualificationResponse {
        allowedVehicleTypes = List.copyOf(allowedVehicleTypes);
    }
}
