package com.bosco.mine.server.modules.notification.model;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据库中的通知接收目标加密快照。
 *
 * @param id 接收对象标识
 * @param groupId 所属接收组标识
 * @param recipientType 接收对象来源类型
 * @param userId 平台用户标识
 * @param displayName 展示名称
 * @param channelType 渠道类型
 * @param targetCiphertext 真实接收目标密文
 * @param targetDigest 密钥化盲索引
 * @param targetMasked 脱敏接收目标
 * @param status 启用或禁用状态
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本号
 */
public record NotificationRecipient(
        UUID id,
        UUID groupId,
        NotificationRecipientType recipientType,
        UUID userId,
        String displayName,
        NotificationChannelType channelType,
        String targetCiphertext,
        String targetDigest,
        String targetMasked,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    /** 返回不包含密文和盲索引的安全日志摘要。 */
    @Override
    public String toString() {
        return "NotificationRecipient[id=" + id
                + ", groupId=" + groupId
                + ", recipientType=" + recipientType
                + ", channelType=" + channelType
                + ", targetMasked=" + targetMasked
                + ", status=" + status
                + ", version=" + version + "]";
    }
}
