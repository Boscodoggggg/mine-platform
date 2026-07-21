package com.bosco.mine.messaging.alarm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 告警触发或恢复时的有界结构化证据。
 *
 * @param observedValue 数值来源的观测值，质量来源为空
 * @param operator 数值来源的比较算子，质量来源为空
 * @param threshold 数值来源的触发阈值，质量来源为空
 * @param recoverThreshold 数值来源的恢复阈值，质量来源为空
 * @param qualityRuleCode 质量来源的规则编码，数值来源为空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlarmEvidencePayload(
        BigDecimal observedValue,
        String operator,
        BigDecimal threshold,
        BigDecimal recoverThreshold,
        String qualityRuleCode
) {

    private static final BigDecimal MAX_ABSOLUTE_VALUE =
            new BigDecimal("1000000000000000");
    private static final Set<String> OPERATORS = Set.of("GT", "GTE", "LT", "LTE");
    private static final Set<String> QUALITY_RULE_CODES = Set.of(
            "FUTURE_TIMESTAMP", "STALE_TIMESTAMP", "REQUIRED_MISSING",
            "BELOW_MINIMUM", "ABOVE_MAXIMUM"
    );

    /** 校验证据中的数值和白名单编码。 */
    public AlarmEvidencePayload {
        requireBounded(observedValue, "observedValue");
        requireBounded(threshold, "threshold");
        requireBounded(recoverThreshold, "recoverThreshold");
        if (operator != null && !OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("告警证据比较算子不受支持");
        }
        if (qualityRuleCode != null && !QUALITY_RULE_CODES.contains(qualityRuleCode)) {
            throw new IllegalArgumentException("告警证据质量规则编码不受支持");
        }
    }

    private static void requireBounded(BigDecimal value, String field) {
        if (value != null
                && (value.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0 || value.scale() > 12)) {
            throw new IllegalArgumentException(field + " 超出告警证据允许范围");
        }
    }
}
