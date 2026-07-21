package com.bosco.mine.server.modules.notification.dto;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 仅供独立敏感权限接口返回的通知渠道明文凭据。
 *
 * @param channelId 渠道标识
 * @param provider 内置 Provider 编码
 * @param secrets 解密后的真实凭据
 * @param version 凭据绑定的渠道版本
 * @param revealedAt 本次授权读取时间
 */
public record NotificationChannelSecretsResponse(
        UUID channelId,
        String provider,
        Map<String, String> secrets,
        long version,
        Instant revealedAt
) {

    /** 校验响应并冻结敏感字段映射。 */
    public NotificationChannelSecretsResponse {
        Objects.requireNonNull(channelId, "通知渠道标识不能为空");
        Objects.requireNonNull(provider, "通知 Provider 不能为空");
        secrets = Map.copyOf(Objects.requireNonNull(secrets, "通知渠道凭据不能为空"));
        Objects.requireNonNull(revealedAt, "通知渠道凭据读取时间不能为空");
    }

    /** 返回不包含任何明文凭据的安全日志摘要。 */
    @Override
    public String toString() {
        return "NotificationChannelSecretsResponse[channelId=" + channelId
                + ", provider=" + provider
                + ", secretKeys=" + secrets.keySet().stream().sorted().toList()
                + ", version=" + version
                + ", revealedAt=" + revealedAt + "]";
    }
}
