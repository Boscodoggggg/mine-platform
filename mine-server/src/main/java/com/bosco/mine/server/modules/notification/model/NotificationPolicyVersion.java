package com.bosco.mine.server.modules.notification.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知升级策略版本快照。
 *
 * @param id 策略版本标识
 * @param policyId 策略根标识
 * @param versionNumber 业务版本号
 * @param state 草稿、已发布或已退役状态
 * @param matchConditionsJson 矿区与规则匹配条件 JSON
 * @param severityFilterJson 告警等级过滤 JSON
 * @param stopOnAck 告警确认后是否停止后续升级
 * @param notifyOnRecovery 告警恢复时是否通知
 * @param publishedAt 发布时间
 * @param publishedBy 发布用户标识
 * @param publicationReason 发布原因
 * @param retiredAt 退役时间
 * @param createdAt 创建时间
 */
public record NotificationPolicyVersion(
        UUID id,
        UUID policyId,
        int versionNumber,
        String state,
        String matchConditionsJson,
        String severityFilterJson,
        boolean stopOnAck,
        boolean notifyOnRecovery,
        Instant publishedAt,
        UUID publishedBy,
        String publicationReason,
        Instant retiredAt,
        Instant createdAt
) {
}
