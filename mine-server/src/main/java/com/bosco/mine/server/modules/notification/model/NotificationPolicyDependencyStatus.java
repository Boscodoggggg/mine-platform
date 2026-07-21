package com.bosco.mine.server.modules.notification.model;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;

import java.util.UUID;

/**
 * 策略步骤发布前依赖状态投影。
 *
 * @param stepId 步骤标识
 * @param channelStatus 渠道状态
 * @param channelType 渠道类型
 * @param recipientGroupStatus 接收组状态
 * @param enabledRecipientCount 同渠道启用接收对象数量
 * @param templateStatus 触发模板状态
 * @param templateChannelType 触发模板渠道类型
 * @param templateEventType 触发模板事件类型
 * @param recoveryTemplateStatus 恢复模板状态
 * @param recoveryTemplateChannelType 恢复模板渠道类型
 * @param recoveryTemplateEventType 恢复模板事件类型
 */
public record NotificationPolicyDependencyStatus(
        UUID stepId,
        String channelStatus,
        NotificationChannelType channelType,
        String recipientGroupStatus,
        int enabledRecipientCount,
        String templateStatus,
        NotificationChannelType templateChannelType,
        NotificationEventType templateEventType,
        String recoveryTemplateStatus,
        NotificationChannelType recoveryTemplateChannelType,
        NotificationEventType recoveryTemplateEventType
) {
}
