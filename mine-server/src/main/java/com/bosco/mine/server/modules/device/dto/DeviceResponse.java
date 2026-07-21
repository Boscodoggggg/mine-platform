package com.bosco.mine.server.modules.device.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备管理响应，不包含任何设备凭据。
 *
 * @param id          设备标识
 * @param productId   所属产品标识
 * @param deviceCode  设备编码
 * @param name        设备名称
 * @param status      设备状态
 * @param archivedAt 归档时间
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @param version     乐观锁版本
 */
public record DeviceResponse(
        UUID id,
        UUID productId,
        String deviceCode,
        String name,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
