package com.bosco.mine.server.modules.notification.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知升级策略版本中的稳定步骤快照。
 *
 * @param id 步骤标识
 * @param policyVersionId 策略版本标识
 * @param stepOrder 从零开始的步骤顺序
 * @param delaySeconds 相对告警产生时间的延迟秒数
 * @param channelId 通知渠道标识
 * @param recipientGroupId 接收组标识
 * @param templateId 告警触发模板标识
 * @param recoveryTemplateId 告警恢复模板标识
 * @param ticketAction 事件单动作
 * @param ticketPriority 事件单优先级
 * @param createdAt 创建时间
 */
public record NotificationPolicyStep(
        UUID id,
        UUID policyVersionId,
        int stepOrder,
        int delaySeconds,
        UUID channelId,
        UUID recipientGroupId,
        UUID templateId,
        UUID recoveryTemplateId,
        String ticketAction,
        Integer ticketPriority,
        Instant createdAt
) {
}
