package com.bosco.mine.server.modules.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 通知升级策略、当前草稿和当前已发布版本响应。
 *
 * @param id 策略标识
 * @param policyCode 稳定策略编码
 * @param name 策略名称
 * @param description 策略说明
 * @param priority 匹配优先级
 * @param status 活跃或归档状态
 * @param draft 当前草稿
 * @param published 当前已发布版本
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param archivedAt 归档时间
 * @param version 聚合乐观锁版本号
 */
public record NotificationPolicyResponse(
        UUID id,
        String policyCode,
        String name,
        String description,
        int priority,
        String status,
        Version draft,
        Version published,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        long version
) {
    /**
     * 策略版本响应。
     *
     * @param id 策略版本标识
     * @param versionNumber 业务版本号
     * @param state 草稿、已发布或已退役状态
     * @param matchConditions 匹配条件
     * @param severityFilter 告警等级集合
     * @param stopOnAck 确认后是否停止升级
     * @param notifyOnRecovery 恢复时是否通知
     * @param publicationReason 发布原因
     * @param publishedAt 发布时间
     * @param steps 升级步骤
     */
    public record Version(
            UUID id,
            int versionNumber,
            String state,
            MatchConditions matchConditions,
            List<String> severityFilter,
            boolean stopOnAck,
            boolean notifyOnRecovery,
            String publicationReason,
            Instant publishedAt,
            List<Step> steps
    ) {
    }

    /**
     * 策略匹配条件响应。
     *
     * @param mineIds 矿区标识集合
     * @param ruleKeys 稳定告警规则编码集合
     */
    public record MatchConditions(Set<UUID> mineIds, Set<String> ruleKeys) {
    }

    /**
     * 策略步骤响应。
     *
     * @param id 步骤标识
     * @param stepOrder 步骤顺序
     * @param delaySeconds 延迟秒数
     * @param channelId 通知渠道标识
     * @param recipientGroupId 接收组标识
     * @param templateId 告警触发模板标识
     * @param recoveryTemplateId 告警恢复模板标识
     * @param ticketAction 事件单动作
     * @param ticketPriority 事件单优先级
     */
    public record Step(
            UUID id,
            int stepOrder,
            int delaySeconds,
            UUID channelId,
            UUID recipientGroupId,
            UUID templateId,
            UUID recoveryTemplateId,
            String ticketAction,
            Integer ticketPriority
    ) {
    }
}
