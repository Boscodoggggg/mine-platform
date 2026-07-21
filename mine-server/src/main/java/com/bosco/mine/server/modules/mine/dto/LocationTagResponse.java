package com.bosco.mine.server.modules.mine.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 定位标签响应。
 *
 * @param id 标签标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param tagCode 标签编码
 * @param vendor 标签厂商
 * @param model 标签型号
 * @param batteryPercent 电池电量百分比
 * @param batteryObservedAt 电量观测时间
 * @param status 生命周期状态
 * @param archivedAt 归档时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record LocationTagResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String tagCode,
        String vendor,
        String model,
        Integer batteryPercent,
        Instant batteryObservedAt,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
