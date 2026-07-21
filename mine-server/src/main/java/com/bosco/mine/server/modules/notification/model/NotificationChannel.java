package com.bosco.mine.server.modules.notification.model;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据库中的通知渠道配置快照。
 *
 * @param id 渠道标识
 * @param channelCode 稳定渠道编码
 * @param name 渠道名称
 * @param channelType 渠道类型
 * @param provider 内置 Provider 编码
 * @param status 启用状态
 * @param configurationJson 非敏感配置 JSON
 * @param secretCiphertext 凭据密文
 * @param connectTimeoutMs 连接超时毫秒数
 * @param readTimeoutMs 读取超时毫秒数
 * @param lastVerifiedAt 最后验证成功时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本号
 */
public record NotificationChannel(
        UUID id,
        String channelCode,
        String name,
        NotificationChannelType channelType,
        String provider,
        String status,
        String configurationJson,
        String secretCiphertext,
        int connectTimeoutMs,
        int readTimeoutMs,
        Instant lastVerifiedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    /** 返回不包含配置 JSON 和凭据密文的安全日志摘要。 */
    @Override
    public String toString() {
        return "NotificationChannel[id=" + id
                + ", channelCode=" + channelCode
                + ", channelType=" + channelType
                + ", provider=" + provider
                + ", status=" + status
                + ", version=" + version + "]";
    }
}
