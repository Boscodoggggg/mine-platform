package com.bosco.mine.telemetry.aggregation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 Telemetry 聚合策略镜像会再次执行共享白名单并生成不可变类型对象。
 */
class AggregationPolicyRepositoryTest {

    @Test
    void 应从规范化镜像加载类型安全聚合规则() {
        UUID snapshotId = UUID.randomUUID();

        AggregationPolicy policy = AggregationPolicy.from(
                snapshotId,
                7,
                "sha256:" + "a".repeat(64),
                Map.of("policy", Map.of(
                        "enabled", true,
                        "correctionWindowSeconds", 7_200L,
                        "rules", List.of(
                                Map.of("propertyId", "speed", "bucketSeconds", 300L),
                                Map.of("propertyId", "payload", "bucketSeconds", 3_600)
                        )
                ))
        );

        assertThat(policy.snapshotId()).isEqualTo(snapshotId);
        assertThat(policy.runtimeRevision()).isEqualTo(7);
        assertThat(policy.enabled()).isTrue();
        assertThat(policy.correctionWindowSeconds()).isEqualTo(7_200);
        assertThat(policy.rules()).containsExactly(
                new AggregationPolicy.Rule("payload", 3_600),
                new AggregationPolicy.Rule("speed", 300)
        );
        assertThatThrownBy(() -> policy.rules().add(
                new AggregationPolicy.Rule("temperature", 900)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 非法镜像不得降级为默认策略() {
        assertThatThrownBy(() -> AggregationPolicy.from(
                UUID.randomUUID(),
                1,
                "sha256:" + "b".repeat(64),
                Map.of("policy", Map.of(
                        "enabled", true,
                        "correctionWindowSeconds", 600,
                        "rules", List.of(
                                Map.of("propertyId", "speed", "bucketSeconds", 120)
                        )
                ))
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 类型安全规则不得绕过属性标识白名单() {
        assertThatThrownBy(() -> new AggregationPolicy.Rule("9speed", 300))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
