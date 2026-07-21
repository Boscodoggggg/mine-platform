package com.bosco.mine.server.modules.device.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 产品管理响应。
 *
 * @param id         产品标识
 * @param productKey 稳定产品编码
 * @param name       产品名称
 * @param deviceType 设备类型
 * @param status     产品状态
 * @param archivedAt 归档时间
 * @param createdAt  创建时间
 * @param updatedAt  更新时间
 * @param version    乐观锁版本
 */
public record ProductResponse(
        UUID id,
        String productKey,
        String name,
        String deviceType,
        String status,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
