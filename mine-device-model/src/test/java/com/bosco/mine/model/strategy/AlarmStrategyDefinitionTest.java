package com.bosco.mine.model.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 校验告警策略只接受数值阈值和数据质量命中的受控规则集合。
 */
class AlarmStrategyDefinitionTest {

    private final AlarmStrategyDefinition definition = new AlarmStrategyDefinition();

    @Test
    void 应规范化数值和数据质量双来源规则() {
        Map<String, Object> values = definition.validate(Map.of("rules", List.of(
                numericRule("engine-temperature-high", "发动机高温", "GT", 95.00D, 90L),
                qualityRule("rpm-required", "转速缺失", "REQUIRED_MISSING", "engine.rpm")
        )));

        assertThat(definition.type()).isEqualTo("ALARM");
        assertThat(values).containsOnlyKeys("rules");
        assertThat((List<?>) values.get("rules")).hasSize(2);
        assertThat(rule(values, 0))
                .containsEntry("sourceType", "NUMERIC")
                .containsEntry("threshold", new BigDecimal("95"))
                .containsEntry("recoverThreshold", new BigDecimal("9E+1"))
                .containsEntry("triggerDurationSeconds", 30)
                .containsEntry("recoverDurationSeconds", 60)
                .containsEntry("cooldownSeconds", 300);
        assertThat(rule(values, 1))
                .containsEntry("sourceType", "QUALITY")
                .containsEntry("qualityRuleCode", "REQUIRED_MISSING")
                .containsEntry("propertyId", "engine.rpm")
                .doesNotContainKeys("operator", "threshold", "recoverThreshold");
    }

    @Test
    void 质量规则应允许匹配任意属性且默认启用() {
        Map<String, Object> rule = qualityRule(
                "any-stale", "任意陈旧时间", "STALE_TIMESTAMP", null);
        rule.remove("enabled");

        Map<String, Object> values = definition.validate(Map.of("rules", List.of(rule)));

        assertThat(rule(values, 0))
                .containsEntry("enabled", true)
                .doesNotContainKey("propertyId");
    }

    @Test
    void 应一次返回顶层字段重复键字段错配和非法迟滞问题() {
        Map<String, Object> wrongNumeric = numericRule(
                "temperature-high", "温度高", "GT", 95, 96);
        wrongNumeric.put("qualityRuleCode", "ABOVE_MAXIMUM");
        Map<String, Object> wrongQuality = qualityRule(
                "temperature-high", "温度质量", "ABOVE_MAXIMUM", "engine.temperature");
        wrongQuality.put("threshold", 95);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rules", List.of(wrongNumeric, wrongQuality));
        values.put("script", "return true");

        StrategyValidationException exception = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values)
        );

        assertThat(exception.violations())
                .extracting(StrategyValidationException.Violation::field)
                .contains(
                        "script",
                        "rules[0].recoverThreshold",
                        "rules[0].qualityRuleCode",
                        "rules[1].key",
                        "rules[1].threshold"
                );
    }

    @Test
    void 应拒绝未知枚举非法属性越界时间非有限数值和空规则集() {
        Map<String, Object> wrongEnum = numericRule(
                "invalid-enum", "非法枚举", "EQ", 1, 1);
        wrongEnum.put("severity", "FATAL");
        Map<String, Object> wrongProperty = numericRule(
                "invalid-property", "非法属性", "LT", 1, 2);
        wrongProperty.put("propertyId", "$rpm");
        wrongProperty.put("triggerDurationSeconds", 86_401);
        wrongProperty.put("threshold", Double.NaN);

        StrategyValidationException enumException = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("rules", List.of(wrongEnum, wrongProperty)))
        );
        StrategyValidationException emptyException = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("rules", List.of()))
        );

        assertThat(enumException.violations())
                .extracting(StrategyValidationException.Violation::field)
                .contains(
                        "rules[0].operator",
                        "rules[0].severity",
                        "rules[1].propertyId",
                        "rules[1].triggerDurationSeconds",
                        "rules[1].threshold"
                );
        assertThat(emptyException.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("rules");
    }

    @Test
    void 应拒绝超过规则上限和非对象规则() {
        List<Object> oversized = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            oversized.add(qualityRule(
                    "quality-" + index, "质量规则" + index, "STALE_TIMESTAMP", null));
        }

        StrategyValidationException tooMany = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("rules", oversized))
        );
        StrategyValidationException notObject = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("rules", List.of("invalid")))
        );

        assertThat(tooMany.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("rules");
        assertThat(notObject.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("rules[0]");
    }

    private Map<String, Object> numericRule(
            String key,
            String name,
            String operator,
            Object threshold,
            Object recoverThreshold
    ) {
        Map<String, Object> rule = commonRule(key, name, "NUMERIC");
        rule.put("propertyId", "engine.temperature");
        rule.put("operator", operator);
        rule.put("threshold", threshold);
        rule.put("recoverThreshold", recoverThreshold);
        return rule;
    }

    private Map<String, Object> qualityRule(
            String key,
            String name,
            String qualityRuleCode,
            String propertyId
    ) {
        Map<String, Object> rule = commonRule(key, name, "QUALITY");
        rule.put("qualityRuleCode", qualityRuleCode);
        if (propertyId != null) {
            rule.put("propertyId", propertyId);
        }
        return rule;
    }

    private Map<String, Object> commonRule(String key, String name, String sourceType) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("key", key);
        rule.put("name", name);
        rule.put("sourceType", sourceType);
        rule.put("severity", "WARNING");
        rule.put("triggerDurationSeconds", 30L);
        rule.put("recoverDurationSeconds", 60);
        rule.put("cooldownSeconds", 300);
        rule.put("enabled", true);
        return rule;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rule(Map<String, Object> values, int index) {
        return (Map<String, Object>) ((List<?>) values.get("rules")).get(index);
    }
}
