package com.bosco.mine.model.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 校验数据质量策略的时间、必填和属性范围白名单。
 */
class DataQualityStrategyDefinitionTest {

    private final DataQualityStrategyDefinition definition = new DataQualityStrategyDefinition();

    @Test
    void 应规范化合法的时间必填和属性范围字段() {
        Map<String, Object> normalized = definition.validate(Map.of(
                "staleAfterSeconds", 300L,
                "futureToleranceSeconds", 15,
                "required.engine.rpm", true,
                "minimum.engine.rpm", 10.500D,
                "maximum.engine.rpm", new BigDecimal("3000.000")
        ));

        assertThat(definition.type()).isEqualTo("DATA_QUALITY");
        assertThat(normalized)
                .containsEntry("staleAfterSeconds", 300)
                .containsEntry("futureToleranceSeconds", 15)
                .containsEntry("required.engine.rpm", true)
                .containsEntry("minimum.engine.rpm", new BigDecimal("10.5"))
                .containsEntry("maximum.engine.rpm", new BigDecimal("3E+3"));
    }

    @Test
    void 应允许下级作用域显式关闭继承的必填要求() {
        assertThat(definition.validate(Map.of("required.engine.rpm", false)))
                .containsEntry("required.engine.rpm", false);
    }

    @Test
    void 应一次返回未知字段非法属性名错误类型和越界时间() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("futureToleranceSeconds", 3601);
        values.put("maximum.$rpm", 100);
        values.put("required.engine.rpm", "true");
        values.put("script", "return true");

        StrategyValidationException exception = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(values)
        );

        assertThat(exception.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly(
                        "futureToleranceSeconds", "maximum.$rpm",
                        "required.engine.rpm", "script"
                );
    }

    @Test
    void 应拒绝非有限数值超大数值和片段内矛盾范围() {
        StrategyValidationException nonFinite = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of("minimum.engine.rpm", Double.NaN))
        );
        StrategyValidationException tooLarge = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of(
                        "maximum.engine.rpm", new BigDecimal("1000000000000001")
                ))
        );
        StrategyValidationException contradictory = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of(
                        "minimum.engine.rpm", 101,
                        "maximum.engine.rpm", 100
                ))
        );

        assertThat(nonFinite.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("minimum.engine.rpm");
        assertThat(tooLarge.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("maximum.engine.rpm");
        assertThat(contradictory.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("minimum.engine.rpm", "maximum.engine.rpm");
    }

    @Test
    void 应拒绝空片段和超过字段数量上限的输入() {
        StrategyValidationException empty = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(Map.of())
        );
        Map<String, Object> oversized = new LinkedHashMap<>();
        for (int index = 0; index < 257; index++) {
            oversized.put("required.property" + index, true);
        }
        StrategyValidationException tooMany = catchThrowableOfType(
                StrategyValidationException.class,
                () -> definition.validate(oversized)
        );

        assertThat(empty.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("$");
        assertThat(tooMany.violations())
                .extracting(StrategyValidationException.Violation::field)
                .containsExactly("$");
    }
}
