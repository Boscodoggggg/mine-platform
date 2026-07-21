package com.bosco.mine.server.modules.notification.dto;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.model.NotificationRecipientType;

import java.time.Instant;
import java.util.UUID;

/**
 * 不包含真实目标、密文或盲索引的通知接收对象响应。
 *
 * @param id 接收对象标识
 * @param groupId 所属接收组标识
 * @param recipientType 接收对象来源类型
 * @param userId 平台用户标识
 * @param displayName 展示名称
 * @param channelType 渠道类型
 * @param targetMasked 脱敏接收目标
 * @param targetConfigured 是否已配置真实目标
 * @param status 启用或禁用状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本号
 */
public record NotificationRecipientResponse(
        UUID id,
        UUID groupId,
        NotificationRecipientType recipientType,
        UUID userId,
        String displayName,
        NotificationChannelType channelType,
        String targetMasked,
        boolean targetConfigured,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
