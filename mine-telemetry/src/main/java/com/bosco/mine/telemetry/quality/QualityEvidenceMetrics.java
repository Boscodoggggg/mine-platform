package com.bosco.mine.telemetry.quality;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 只按稳定规则编码和质量结果累计命中的低基数 Micrometer 指标。
 */
public final class QualityEvidenceMetrics implements QualityEvidenceObserver {

    private final Map<QualityRuleCode, Map<Quality, Counter>> counters;

    /**
     * 创建质量规则命中指标。
     *
     * @param meterRegistry Micrometer 指标注册表
     */
    public QualityEvidenceMetrics(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "指标注册表不能为空");
        Map<QualityRuleCode, Map<Quality, Counter>> byRule =
                new EnumMap<>(QualityRuleCode.class);
        for (QualityRuleCode ruleCode : QualityRuleCode.values()) {
            Map<Quality, Counter> byResult = new EnumMap<>(Quality.class);
            for (Quality result : Quality.values()) {
                if (result == Quality.GOOD) {
                    continue;
                }
                byResult.put(result, Counter.builder("mine.telemetry.quality.rule.hits")
                        .description("数据库已提交的数据质量规则命中数")
                        .tag("rule", ruleCode.name())
                        .tag("result", result.name())
                        .register(meterRegistry));
            }
            byRule.put(ruleCode, Map.copyOf(byResult));
        }
        this.counters = Map.copyOf(byRule);
    }

    /** 记录一条已提交且幂等去重的质量证据。 */
    @Override
    public void observe(QualityEvidence evidence) {
        Objects.requireNonNull(evidence, "质量证据不能为空");
        Counter counter = counters.get(evidence.ruleCode()).get(evidence.resultQuality());
        if (counter == null) {
            throw new IllegalArgumentException("质量证据结果不支持指标累计");
        }
        counter.increment();
    }
}
