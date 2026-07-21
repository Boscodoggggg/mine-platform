package com.bosco.mine.model.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 校验在线状态策略只接收白名单字段和安全整数范围。
 */
class OnlineStatusStrategyDefinitionTest {

    private final OnlineStatusStrategyDefinition definition = new OnlineStatusStrategyDefinition();

    @Test
    void 应规范化合法离线阈值() {
        Map<String, Object> normalized = definition.validate(Map.of("offlineThresholdSeconds", 180L));

        assertThat(definition.type()).isEqualTo("ONLINE_STATUS");
        assertThat(normalized).containsOnlyKeys("offlineThresholdSeconds");
        assertThat(normalized.get("offlineThresholdSeconds"))
                .isInstanceOf(Integer.class)
                .isEqualTo(180);
    }

    @Test
    void 应一次拒绝未知字段错误类型和范围外阈值() {
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("offlineThresholdSeconds", "180");
        unknown.put("script", "return true");
        StrategyValidationException wrongType = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(unknown)
        );
        StrategyValidationException decimal = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("offlineThresholdSeconds", new BigDecimal("180.5")))
        );
        StrategyValidationException tooSmall = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("offlineThresholdSeconds", 0))
        );
        StrategyValidationException tooLarge = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("offlineThresholdSeconds", 604801L))
        );

        assertThat(wrongType.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("offlineThresholdSeconds", "script");
        assertThat(decimal.violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("offlineThresholdSeconds");
        assertThat(tooSmall.violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("offlineThresholdSeconds");
        assertThat(tooLarge.violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("offlineThresholdSeconds");
    }

    @Test
    void 应拒绝空片段和超过字段数量上限的输入() {
        StrategyValidationException empty = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of())
        );
        Map<String, Object> oversized = new LinkedHashMap<>();
        for (int index = 0; index < 33; index++) {
            oversized.put("field" + index, index);
        }
        StrategyValidationException tooMany = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(oversized)
        );

        assertThat(empty.violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("$");
        assertThat(tooMany.violations()).extracting(StrategyValidationException.Violation::field)
                .containsExactly("$");
    }
}
