package com.bosco.mine.server.modules.mine.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 司机车型资质持久化快照。
 *
 * @param id 资质标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param personId 人员标识
 * @param qualificationCode 资质编码
 * @param name 资质名称
 * @param allowedVehicleTypesJson 允许车型 JSON
 * @param validFrom 有效开始日期
 * @param validTo 有效结束日期
 * @param status 资质状态
 * @param revokedAt 吊销时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record MineDriverQualification(
        UUID id,
        UUID organizationId,
        UUID siteId,
        UUID personId,
        String qualificationCode,
        String name,
        String allowedVehicleTypesJson,
        LocalDate validFrom,
        LocalDate validTo,
        String status,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
