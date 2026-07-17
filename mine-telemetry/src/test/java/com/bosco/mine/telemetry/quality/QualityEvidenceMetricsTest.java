package com.bosco.mine.telemetry.quality;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验质量规则命中指标只使用规则编码和结果两个低基数标签。
 */
class QualityEvidenceMetricsTest {

    @Test
    void 应按规则和结果累计命中且不暴露设备与属性标签() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QualityEvidenceMetrics metrics = new QualityEvidenceMetrics(registry);
        QualityEvidence evidence = new QualityEvidence(
                UUID.fromString("019f6e00-0000-7000-8000-000000000151"),
                UUID.fromString("019f6e00-0000-7000-8000-000000000152"),
                "engine.rpm",
                QualityRuleCode.BELOW_MINIMUM,
                Quality.BAD,
                BigDecimal.valueOf(800),
                BigDecimal.valueOf(1000),
                7L,
                UUID.fromString("019f6e00-0000-7000-8000-000000000153"),
                "sha256:" + "a".repeat(64)
        );

        metrics.observe(evidence);

        var counter = registry.get("mine.telemetry.quality.rule.hits")
                .tag("rule", "BELOW_MINIMUM")
                .tag("result", "BAD")
                .counter();
        assertThat(counter.count()).isOne();
        assertThat(counter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactlyInAnyOrder("rule", "result");
    }
}
