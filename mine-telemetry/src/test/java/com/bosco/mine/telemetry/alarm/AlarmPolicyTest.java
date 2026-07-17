package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.telemetry.quality.QualityRuleCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验告警策略镜像能够再次编译为类型安全的热路径对象。
 */
class AlarmPolicyTest {

    @Test
    void 应编译并冻结数值与质量双来源规则() {
        UUID snapshotId = UUID.randomUUID();
        AlarmPolicy policy = AlarmPolicy.from(
                snapshotId,
                7,
                "sha256:" + "a".repeat(64),
                Map.of("rules", List.of(numericRule(), qualityRule()))
        );

        assertThat(policy.snapshotId()).isEqualTo(snapshotId);
        assertThat(policy.runtimeRevision()).isEqualTo(7);
        assertThat(policy.rules()).hasSize(2);
        assertThat(policy.rules().getFirst()).satisfies(rule -> {
            assertThat(rule.key()).isEqualTo("temperature-high");
            assertThat(rule.sourceType()).isEqualTo(AlarmPolicy.SourceType.NUMERIC);
            assertThat(rule.severity()).isEqualTo(AlarmPolicy.Severity.CRITICAL);
            assertThat(rule.operator()).isEqualTo(AlarmPolicy.Operator.GT);
            assertThat(rule.threshold()).isEqualByComparingTo(new BigDecimal("95"));
            assertThat(rule.recoverThreshold()).isEqualByComparingTo(new BigDecimal("90"));
            assertThat(rule.enabled()).isTrue();
        });
        assertThat(policy.rules().getLast()).satisfies(rule -> {
            assertThat(rule.sourceType()).isEqualTo(AlarmPolicy.SourceType.QUALITY);
            assertThat(rule.qualityRuleCode()).isEqualTo(QualityRuleCode.REQUIRED_MISSING);
            assertThat(rule.propertyId()).isEqualTo("engine.rpm");
            assertThat(rule.operator()).isNull();
            assertThat(rule.threshold()).isNull();
        });
    }

    private Map<String, Object> numericRule() {
        Map<String, Object> rule = commonRule(
                "temperature-high", "发动机高温", "NUMERIC", "CRITICAL");
        rule.put("propertyId", "engine.temperature");
        rule.put("operator", "GT");
        rule.put("threshold", 95);
        rule.put("recoverThreshold", 90);
        return rule;
    }

    private Map<String, Object> qualityRule() {
        Map<String, Object> rule = commonRule(
                "rpm-required", "转速缺失", "QUALITY", "WARNING");
        rule.put("propertyId", "engine.rpm");
        rule.put("qualityRuleCode", "REQUIRED_MISSING");
        return rule;
    }

    private Map<String, Object> commonRule(
            String key,
            String name,
            String sourceType,
            String severity
    ) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("key", key);
        rule.put("name", name);
        rule.put("sourceType", sourceType);
        rule.put("severity", severity);
        rule.put("triggerDurationSeconds", 30);
        rule.put("recoverDurationSeconds", 60);
        rule.put("cooldownSeconds", 300);
        return rule;
    }
}
