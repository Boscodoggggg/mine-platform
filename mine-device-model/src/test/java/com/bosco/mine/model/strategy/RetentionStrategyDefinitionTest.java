package com.bosco.mine.model.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 校验数据保留策略只接受安全启停、受控等级和有界留存天数。
 */
class RetentionStrategyDefinitionTest {

    private final RetentionStrategyDefinition definition = new RetentionStrategyDefinition();

    @Test
    void 应规范化四种保留等级及其整数天数() {
        assertPolicy("SHORT", 7L, 30, 7, 30);
        assertPolicy("STANDARD", new BigDecimal("90"), 180L, 90, 180);
        assertPolicy("LONG", 365, 730, 365, 730);
        assertPolicy("ARCHIVE", 1825, 3650, 1825, 3650);
    }

    @Test
    void 应保留安全暂停语义并冻结嵌套策略对象() {
        Map<String, Object> normalized = definition.validate(values(
                false, "standard", 90, 180));

        assertThat(definition.type()).isEqualTo("RETENTION");
        assertThat(policy(normalized))
                .containsEntry("enabled", false)
                .containsEntry("retentionClass", "STANDARD");
        assertThatThrownBy(() -> policy(normalized).put("enabled", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 应一次返回顶层未知字段缺失字段和内部未知字段() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("enabled", true);
        policy.put("retentionClass", "STANDARD");
        policy.put("rawTelemetryDays", 90);
        policy.put("script", "delete everything");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("policy", policy);
        values.put("cron", "* * * * *");

        StrategyValidationException exception = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values)
        );

        assertThat(exception.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly(
                        "cron", "policy.qualityEvidenceDays", "policy.script"
                );
    }

    @Test
    void 应拒绝等级区间错配非整数和证据短于原始数据() {
        StrategyValidationException wrongClass = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values(true, "SHORT", 31, 31))
        );
        StrategyValidationException fractional = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values(true, "STANDARD", 90.5D, 180))
        );
        StrategyValidationException evidenceTooShort = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values(true, "LONG", 365, 364))
        );

        assertThat(wrongClass.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy.rawTelemetryDays");
        assertThat(fractional.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy.rawTelemetryDays");
        assertThat(evidenceTooShort.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy.qualityEvidenceDays");
    }

    @Test
    void 应拒绝空策略非对象非法布尔值和未知等级() {
        assertThat(catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of())
        ).violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("$");
        assertThat(catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("policy", "STANDARD"))
        ).violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy");
        assertThat(catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values("true", "STANDARD", 90, 180))
        ).violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy.enabled");
        assertThat(catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values(true, "FOREVER", 90, 180))
        ).violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy.retentionClass");
    }

    private void assertPolicy(
            String retentionClass,
            Object rawDays,
            Object evidenceDays,
            int expectedRawDays,
            int expectedEvidenceDays
    ) {
        Map<String, Object> normalized = definition.validate(values(
                true, retentionClass.toLowerCase(), rawDays, evidenceDays));

        assertThat(normalized).containsOnlyKeys("policy");
        assertThat(policy(normalized))
                .containsEntry("enabled", true)
                .containsEntry("retentionClass", retentionClass)
                .containsEntry("rawTelemetryDays", expectedRawDays)
                .containsEntry("qualityEvidenceDays", expectedEvidenceDays);
    }

    private Map<String, Object> values(
            Object enabled,
            Object retentionClass,
            Object rawDays,
            Object evidenceDays
    ) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("enabled", enabled);
        policy.put("retentionClass", retentionClass);
        policy.put("rawTelemetryDays", rawDays);
        policy.put("qualityEvidenceDays", evidenceDays);
        return Map.of("policy", policy);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> policy(Map<String, Object> values) {
        return (Map<String, Object>) values.get("policy");
    }
}
