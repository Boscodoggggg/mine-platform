package com.bosco.mine.messaging.alarm;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 集中校验触发和恢复事件共享的告警身份与证据不变量。
 */
final class AlarmPayloadSupport {

    private static final Pattern PRODUCT_KEY = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final Pattern RULE_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern PROPERTY_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");
    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> SOURCE_TYPES = Set.of("NUMERIC", "QUALITY");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");

    private AlarmPayloadSupport() {
    }

    /** 校验告警事件完整身份和双来源字段矩阵。 */
    static void validate(
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
        Objects.requireNonNull(alarmOccurrenceId, "告警周期标识不能为空");
        if (alarmOccurrenceId.version() != 7) {
            throw new IllegalArgumentException("告警周期标识必须是 UUIDv7");
        }
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        requirePattern(productKey, PRODUCT_KEY, "产品编码");
        requirePattern(ruleKey, RULE_KEY, "告警规则编码");
        requireText(ruleName, 100, "告警规则名称");
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw new IllegalArgumentException("告警来源不受支持");
        }
        if (!SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("告警等级不受支持");
        }
        if (propertyId != null) {
            requirePattern(propertyId, PROPERTY_ID, "告警属性编码");
        }
        Objects.requireNonNull(evidence, "告警证据不能为空");
        validateEvidence(sourceType, propertyId, evidence);
        if (policyRuntimeRevision < 1) {
            throw new IllegalArgumentException("告警策略运行修订号必须大于 0");
        }
        Objects.requireNonNull(policySnapshotId, "告警策略快照标识不能为空");
        requirePattern(policyContentHash, HASH, "告警策略内容摘要");
        Objects.requireNonNull(raisedAt, "告警触发时间不能为空");
    }

    private static void validateEvidence(
            String sourceType,
            String propertyId,
            AlarmEvidencePayload evidence
    ) {
        if ("NUMERIC".equals(sourceType)) {
            if (propertyId == null
                    || evidence.observedValue() == null
                    || evidence.operator() == null
                    || evidence.threshold() == null
                    || evidence.recoverThreshold() == null
                    || evidence.qualityRuleCode() != null) {
                throw new IllegalArgumentException("数值告警证据字段不完整或发生来源错配");
            }
            return;
        }
        if (evidence.qualityRuleCode() == null
                || evidence.observedValue() != null
                || evidence.operator() != null
                || evidence.threshold() != null
                || evidence.recoverThreshold() != null) {
            throw new IllegalArgumentException("质量告警证据字段不完整或发生来源错配");
        }
    }

    private static void requireText(String value, int maximumLength, String label) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(label + "长度不合法");
        }
    }

    private static void requirePattern(String value, Pattern pattern, String label) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "格式不合法");
        }
    }
}
