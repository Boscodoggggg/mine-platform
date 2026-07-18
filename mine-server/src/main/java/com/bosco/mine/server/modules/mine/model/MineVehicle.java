package com.bosco.mine.server.modules.mine.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 矿区车辆持久化快照。
 *
 * @param id 车辆标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param vehicleCode 车辆编码
 * @param name 车辆名称
 * @param vehicleType 车辆类型
 * @param mineNumber 矿山内部编号
 * @param licensePlate 车辆牌照
 * @param brand 车辆品牌
 * @param model 车辆型号
 * @param ratedLoadTons 额定载重吨数
 * @param status 生命周期状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param archivedAt 归档时间
 * @param version 乐观锁版本
 */
public record MineVehicle(
        UUID id,
        UUID organizationId,
        UUID siteId,
        String vehicleCode,
        String name,
        String vehicleType,
        String mineNumber,
        String licensePlate,
        String brand,
        String model,
        BigDecimal ratedLoadTons,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        long version
) {
}
