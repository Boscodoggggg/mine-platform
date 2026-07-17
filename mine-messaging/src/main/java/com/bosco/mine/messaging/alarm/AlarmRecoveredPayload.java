package com.bosco.mine.messaging.alarm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 可在触发事件之前独立落库的告警恢复事件载荷。
 *
 * @param alarmOccurrenceId 告警周期 UUIDv7 标识
 * @param deviceId 设备标识
 * @param productKey 稳定产品编码
 * @param ruleKey 稳定规则编码
 * @param ruleName 值班端规则名称快照
 * @param sourceType 告警来源
 * @param severity 告警等级
 * @param propertyId 属性编码，质量规则可为空
 * @param evidence 恢复时结构化证据
 * @param policyRuntimeRevision 策略运行修订号
 * @param policySnapshotId 不可变策略快照标识
 * @param policyContentHash 单设备策略内容摘要
 * @param raisedAt 告警触发时间
 * @param recoveredAt 告警恢复时间
 * @param recoveryReason 恢复原因
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlarmRecoveredPayload(
        UUID alarmOccurrenceId,
        UUID deviceId,
        String productKey,
        String ruleKey,
        String ruleName,
        String sourceType,
        String severity,
        String propertyId,
        AlarmEvidencePayload evidence,
        long policyRuntimeRevision,
        UUID policySnapshotId,
        String policyContentHash,
        Instant raisedAt,
        Instant recoveredAt,
        String recoveryReason
) {

    private static final Set<String> RECOVERY_REASONS = Set.of(
            "CONDITION_CLEARED", "RULE_DISABLED", "RULE_REMOVED", "RULE_CHANGED"
    );

    /** 校验恢复事件完整身份、时间顺序和原因白名单。 */
    public AlarmRecoveredPayload {
        AlarmPayloadSupport.validate(
                alarmOccurrenceId, deviceId, productKey, ruleKey, ruleName,
                sourceType, severity, propertyId, evidence, policyRuntimeRevision,
                policySnapshotId, policyContentHash, raisedAt);
        Objects.requireNonNull(recoveredAt, "告警恢复时间不能为空");
        if (recoveredAt.isBefore(raisedAt)) {
            throw new IllegalArgumentException("告警恢复时间不能早于触发时间");
        }
        if (!RECOVERY_REASONS.contains(recoveryReason)) {
            throw new IllegalArgumentException("告警恢复原因不受支持");
        }
    }
}
