package com.bosco.mine.model.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 校验聚合策略只接受固定时间桶、属性白名单和有界迟到修正窗口。
 */
class AggregationStrategyDefinitionTest {

    private final AggregationStrategyDefinition definition =
            new AggregationStrategyDefinition();

    @Test
    void 应按属性和桶宽稳定规范化原子策略() {
        Map<String, Object> normalized = definition.validate(values(
                true,
                7_200L,
                List.of(
                        rule("speed", 300L),
                        rule("payload", new BigDecimal("3600"))
                )
        ));

        assertThat(definition.type()).isEqualTo("AGGREGATION");
        assertThat(policy(normalized))
                .containsEntry("enabled", true)
                .containsEntry("correctionWindowSeconds", 7_200);
        assertThat(rules(normalized)).containsExactly(
                Map.of("bucketSeconds", 3_600, "propertyId", "payload"),
                Map.of("bucketSeconds", 300, "propertyId", "speed")
        );
    }

    @Test
    void 应冻结规范化策略及规则集合() {
        Map<String, Object> normalized = definition.validate(values(
                false, 600, List.of(rule("speed", 60))));

        assertThatThrownBy(() -> policy(normalized).put("enabled", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rules(normalized).add(rule("payload", 300)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rules(normalized).getFirst().put("bucketSeconds", 900))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 应一次返回未知字段缺失字段和规则未知字段() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("enabled", true);
        policy.put("rules", List.of(Map.of(
                "propertyId", "speed",
                "bucketSeconds", 300,
                "sql", "DROP TABLE telemetry_numeric"
        )));
        policy.put("cron", "* * * * *");
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("policy", policy);
        candidate.put("expression", "speed / 0");

        StrategyValidationException exception = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(candidate)
        );

        assertThat(exception.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly(
                        "expression",
                        "policy.correctionWindowSeconds",
                        "policy.cron",
                        "policy.rules[0].sql"
                );
    }

    @Test
    void 应拒绝非法属性桶宽重复规则和浮点整数() {
        StrategyValidationException exception = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values(
                        true,
                        7_200.0D,
                        List.of(
                                rule("9speed", 120),
                                rule("payload", 300),
                                rule("payload", 300)
                        )
                ))
        );

        assertThat(exception.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly(
                        "policy.correctionWindowSeconds",
                        "policy.rules[0].propertyId",
                        "policy.rules[0].bucketSeconds",
                        "policy.rules[2]"
                );
    }

    @Test
    void 修正窗口必须覆盖最大桶宽两倍() {
        StrategyValidationException exception = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values(
                        true, 7_199, List.of(rule("payload", 3_600))))
        );

        assertThat(exception.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy.correctionWindowSeconds");
    }

    @Test
    void 应拒绝非对象策略和越界规则数量() {
        assertThat(catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of())
        ).violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("$");
        assertThat(catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("policy", "PT1H"))
        ).violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy");
        assertThat(catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values(true, 600, List.of()))
        ).violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy.rules");

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            tooMany.add(rule("property." + index, 60));
        }
        assertThat(catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values(true, 600, tooMany))
        ).violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("policy.rules");
    }

    private Map<String, Object> values(
            Object enabled,
            Object correctionWindowSeconds,
            Object rules
    ) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("enabled", enabled);
        policy.put("correctionWindowSeconds", correctionWindowSeconds);
        policy.put("rules", rules);
        return Map.of("policy", policy);
    }

    private Map<String, Object> rule(String propertyId, Object bucketSeconds) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("propertyId", propertyId);
        rule.put("bucketSeconds", bucketSeconds);
        return rule;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> policy(Map<String, Object> values) {
        return (Map<String, Object>) values.get("policy");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rules(Map<String, Object> values) {
        return (List<Map<String, Object>>) policy(values).get("rules");
    }
}
