package com.bosco.mine.server.modules.notification.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 仅供独立敏感权限接口返回的通知接收目标明文。
 *
 * @param recipientId 接收对象标识
 * @param target 真实接收目标
 * @param version 目标密文绑定的版本号
 * @param revealedAt 本次授权读取时间
 */
public record NotificationRecipientTargetResponse(
        UUID recipientId,
        String target,
        long version,
        Instant revealedAt
) {
    /** 校验敏感响应必要字段。 */
    public NotificationRecipientTargetResponse {
        Objects.requireNonNull(recipientId, "通知接收对象标识不能为空");
        Objects.requireNonNull(target, "通知接收目标不能为空");
        Objects.requireNonNull(revealedAt, "通知接收目标读取时间不能为空");
    }

    /** 返回不包含真实接收目标的安全日志摘要。 */
    @Override
    public String toString() {
        return "NotificationRecipientTargetResponse[recipientId=" + recipientId
                + ", version=" + version
                + ", revealedAt=" + revealedAt + "]";
    }
}
