package com.bosco.mine.messaging.alarm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * 可独立落库的告警触发事件载荷。
 *
 * @param alarmOccurrenceId 告警周期 UUIDv7 标识
 * @param deviceId 设备标识
 * @param productKey 稳定产品编码
 * @param ruleKey 稳定规则编码
 * @param ruleName 值班端规则名称快照
 * @param sourceType 告警来源
 * @param severity 告警等级
 * @param propertyId 属性编码，质量规则可为空
 * @param evidence 触发时结构化证据
 * @param policyRuntimeRevision 策略运行修订号
 * @param policySnapshotId 不可变策略快照标识
 * @param policyContentHash 单设备策略内容摘要
 * @param raisedAt 告警触发时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlarmRaisedPayload(
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
        Instant raisedAt
) {

    /** 校验告警触发事件的完整身份和来源字段矩阵。 */
    public AlarmRaisedPayload {
        AlarmPayloadSupport.validate(
                alarmOccurrenceId, deviceId, productKey, ruleKey, ruleName,
                sourceType, severity, propertyId, evidence, policyRuntimeRevision,
                policySnapshotId, policyContentHash, raisedAt);
    }
}
