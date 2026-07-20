package com.bosco.mine.server.modules.mine.operation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 矿区班次模板响应。
 *
 * @param id 模板标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param definitionCode 模板编码
 * @param name 班次名称
 * @param startLocal 矿区本地开始时间
 * @param endLocal 矿区本地结束时间
 * @param crossesMidnight 是否跨午夜
 * @param validFrom 生效营业日期
 * @param validTo 失效营业日期
 * @param status 生命周期状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param archivedAt 归档时间
 * @param version 乐观锁版本
 */
public record ShiftDefinitionResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String definitionCode,
        String name,
        LocalTime startLocal,
        LocalTime endLocal,
        boolean crossesMidnight,
        LocalDate validFrom,
        LocalDate validTo,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        long version
) {
}
