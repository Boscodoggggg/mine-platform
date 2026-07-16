package com.bosco.mine.model.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验产品、设备组和单设备策略能够确定性合并并阻止同优先级冲突。
 */
class EffectiveStrategyResolverTest {

    private static final UUID PRODUCT_ID = UUID.fromString("019f6a12-a000-7000-8000-000000000100");
    private static final UUID DEVICE_ID = UUID.fromString("019f6a12-a000-7000-8000-000000000200");
    private static final UUID GROUP_A = UUID.fromString("019f6a12-a000-7000-8000-000000000301");
    private static final UUID GROUP_B = UUID.fromString("019f6a12-a000-7000-8000-000000000302");

    private final EffectiveStrategyResolver resolver = new EffectiveStrategyResolver();

    @Test
    void 设备值应覆盖高优先级组且高优先级组应覆盖产品默认值() {
        var result = resolver.resolve(List.of(
                fragment(StrategyScopeType.PRODUCT, PRODUCT_ID, null, Map.of(
                        "offlineThresholdSeconds", 120,
                        "lateArrivalToleranceSeconds", 5
                )),
                fragment(StrategyScopeType.GROUP, GROUP_A, 10, Map.of(
                        "offlineThresholdSeconds", 180,
                        "lateArrivalToleranceSeconds", 10
                )),
                fragment(StrategyScopeType.GROUP, GROUP_B, 80, Map.of(
                        "offlineThresholdSeconds", 300
                )),
                fragment(StrategyScopeType.DEVICE, DEVICE_ID, null, Map.of(
                        "lateArrivalToleranceSeconds", 20
                ))
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.values())
                .containsEntry("offlineThresholdSeconds", 300)
                .containsEntry("lateArrivalToleranceSeconds", 20);
        assertThat(result.sources().get("offlineThresholdSeconds"))
                .extracting(
                        EffectiveStrategyResolver.ValueSource::scopeType,
                        EffectiveStrategyResolver.ValueSource::scopeId,
                        EffectiveStrategyResolver.ValueSource::groupPriority
                )
                .containsExactly(StrategyScopeType.GROUP, GROUP_B, 80);
        assertThat(result.sources().get("lateArrivalToleranceSeconds").scopeType())
                .isEqualTo(StrategyScopeType.DEVICE);
        assertThat(result.contentHash()).startsWith("sha256:").hasSize(71);
    }

    @Test
    void 同优先级组同字段异值必须返回稳定冲突且不产生有效值() {
        var result = resolver.resolve(List.of(
                fragment(StrategyScopeType.PRODUCT, PRODUCT_ID, null, Map.of("offlineThresholdSeconds", 120)),
                fragment(StrategyScopeType.GROUP, GROUP_B, 50, Map.of("offlineThresholdSeconds", 240)),
                fragment(StrategyScopeType.GROUP, GROUP_A, 50, Map.of("offlineThresholdSeconds", 180))
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.values()).isEmpty();
        assertThat(result.sources()).isEmpty();
        assertThat(result.contentHash()).isNull();
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.field()).isEqualTo("offlineThresholdSeconds");
            assertThat(conflict.groupPriority()).isEqualTo(50);
            assertThat(conflict.groupIds()).containsExactly(GROUP_A, GROUP_B);
        });
    }

    @Test
    void 同优先级相同值和不同字段应允许合并() {
        var result = resolver.resolve(List.of(
                fragment(StrategyScopeType.GROUP, GROUP_B, 50, Map.of(
                        "offlineThresholdSeconds", 180,
                        "lateArrivalToleranceSeconds", 10
                )),
                fragment(StrategyScopeType.GROUP, GROUP_A, 50, Map.of("offlineThresholdSeconds", 180))
        ));

        assertThat(result.valid()).isTrue();
        assertThat(result.values())
                .containsEntry("offlineThresholdSeconds", 180)
                .containsEntry("lateArrivalToleranceSeconds", 10);
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.sources().get("offlineThresholdSeconds").scopeId()).isEqualTo(GROUP_A);
    }

    @Test
    void 输入顺序和字段顺序不得改变结果与哈希() {
        Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put("ratio", new BigDecimal("1.20"));
        firstOrder.put("offlineThresholdSeconds", 180);
        Map<String, Object> secondOrder = new LinkedHashMap<>();
        secondOrder.put("offlineThresholdSeconds", 180);
        secondOrder.put("ratio", new BigDecimal("1.2"));
        var product = fragment(StrategyScopeType.PRODUCT, PRODUCT_ID, null, firstOrder);
        var group = fragment(StrategyScopeType.GROUP, GROUP_A, 30, Map.of("offlineThresholdSeconds", 240));
        var equivalentProduct = new ScopedStrategyFragment(
                product.releaseId(), StrategyScopeType.PRODUCT, PRODUCT_ID, null, secondOrder
        );

        var forward = resolver.resolve(List.of(product, group));
        var reversed = resolver.resolve(List.of(group, equivalentProduct));

        assertThat(forward.valid()).isTrue();
        assertThat(reversed.valid()).isTrue();
        assertThat(forward.values()).isEqualTo(reversed.values());
        assertThat(forward.sources()).isEqualTo(reversed.sources());
        assertThat(forward.contentHash()).isEqualTo(reversed.contentHash());
    }

    private ScopedStrategyFragment fragment(
            StrategyScopeType scopeType,
            UUID scopeId,
            Integer groupPriority,
            Map<String, Object> values
    ) {
        return new ScopedStrategyFragment(
                UUID.nameUUIDFromBytes((scopeType + scopeId.toString()).getBytes()),
                scopeType,
                scopeId,
                groupPriority,
                values
        );
    }
}
