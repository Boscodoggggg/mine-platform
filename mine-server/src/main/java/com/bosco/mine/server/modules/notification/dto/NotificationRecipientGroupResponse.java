package com.bosco.mine.server.modules.notification.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知接收组响应。
 *
 * @param id 接收组标识
 * @param groupCode 稳定接收组编码
 * @param name 接收组名称
 * @param description 接收组说明
 * @param status 启用或禁用状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本号
 */
public record NotificationRecipientGroupResponse(
        UUID id,
        String groupCode,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
