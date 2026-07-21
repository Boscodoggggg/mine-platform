package com.bosco.mine.server.modules.notification.model;

import java.util.UUID;

/**
 * 同优先级已发布策略的冲突检测投影。
 *
 * @param policyId 策略标识
 * @param policyCode 策略编码
 * @param matchConditionsJson 匹配条件 JSON
 * @param severityFilterJson 等级过滤 JSON
 */
public record NotificationPolicyOverlapCandidate(
        UUID policyId,
        String policyCode,
        String matchConditionsJson,
        String severityFilterJson
) {
}
