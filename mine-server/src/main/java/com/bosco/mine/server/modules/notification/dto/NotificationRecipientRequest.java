package com.bosco.mine.server.modules.notification.dto;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.model.NotificationRecipientType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 通知接收对象创建或修改请求。
 *
 * @param recipientType 接收对象来源类型
 * @param userId 平台用户标识，仅系统用户类型允许
 * @param displayName 展示名称
 * @param channelType 渠道类型
 * @param target 真实接收目标，修改时为空表示保留原目标
 * @param status 启用或禁用状态
 * @param version 期望乐观锁版本号
 */
public record NotificationRecipientRequest(
        @NotNull NotificationRecipientType recipientType,
        UUID userId,
        @NotBlank @Size(max = 128) String displayName,
        @NotNull NotificationChannelType channelType,
        @Size(max = 2048) String target,
        @NotBlank @Pattern(regexp = "ENABLED|DISABLED") String status,
        @Min(0) long version
) {
    /** 返回不包含真实接收目标的安全日志摘要。 */
    @Override
    public String toString() {
        return "NotificationRecipientRequest[recipientType=" + recipientType
                + ", userId=" + userId
                + ", channelType=" + channelType
                + ", status=" + status
                + ", targetConfigured=" + (target != null && !target.isBlank())
                + ", version=" + version + "]";
    }
}
