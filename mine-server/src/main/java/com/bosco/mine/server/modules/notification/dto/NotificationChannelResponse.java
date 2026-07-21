package com.bosco.mine.server.modules.notification.dto;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 不包含凭据明文或密文的通知渠道响应。
 *
 * @param id 渠道标识
 * @param channelCode 稳定渠道编码
 * @param name 渠道名称
 * @param channelType 渠道类型
 * @param provider 内置 Provider 编码
 * @param status 启用状态
 * @param configuration 非敏感配置
 * @param secretConfigured 是否已经配置凭据
 * @param connectTimeoutMs 连接超时毫秒数
 * @param readTimeoutMs 读取超时毫秒数
 * @param lastVerifiedAt 最后验证成功时间
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本号
 */
public record NotificationChannelResponse(
        UUID id,
        String channelCode,
        String name,
        NotificationChannelType channelType,
        String provider,
        String status,
        Map<String, Object> configuration,
        boolean secretConfigured,
        int connectTimeoutMs,
        int readTimeoutMs,
        Instant lastVerifiedAt,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
