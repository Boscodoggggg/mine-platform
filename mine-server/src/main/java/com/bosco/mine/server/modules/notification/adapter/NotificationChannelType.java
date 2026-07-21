package com.bosco.mine.server.modules.notification.adapter;

/**
 * 平台支持的稳定通知渠道类型。
 */
public enum NotificationChannelType {
    /** 电子邮件。 */
    EMAIL,
    /** 短信。 */
    SMS,
    /** 语音电话。 */
    VOICE,
    /** 企业即时通讯。 */
    ENTERPRISE_IM,
    /** 通用 HTTP Webhook。 */
    WEBHOOK
}
