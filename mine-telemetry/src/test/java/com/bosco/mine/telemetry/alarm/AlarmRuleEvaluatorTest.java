package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import com.bosco.mine.telemetry.quality.DataQualityPolicy;
import com.bosco.mine.telemetry.quality.QualityEvaluationCoverage;
import com.bosco.mine.telemetry.quality.QualityEvidence;
import com.bosco.mine.telemetry.quality.QualityRuleCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验数值迟滞与数据质量命中两类规则的纯函数判定。
 */
class AlarmRuleEvaluatorTest {

    private static final UUID EVENT_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000101");
    private static final UUID DEVICE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000102");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000103");
    private static final Instant AT = Instant.parse("2026-07-17T04:00:00Z");

    private final AlarmRuleEvaluator evaluator = new AlarmRuleEvaluator();

    @Test
    void 数值高值规则应区分触发迟滞区和恢复() {
        AlarmPolicy.Rule rule = numericRule();

        assertThat(evaluator.evaluate(
                rule, List.of(sample(96, Quality.GOOD)), List.of(),
                QualityEvaluationCoverage.none())
                .outcome()).isEqualTo(AlarmEvaluation.Outcome.FIRING);
        assertThat(evaluator.evaluate(
                rule, List.of(sample(92, Quality.GOOD)), List.of(),
                QualityEvaluationCoverage.none())
                .outcome()).isEqualTo(AlarmEvaluation.Outcome.BETWEEN);
        AlarmEvaluation cleared = evaluator.evaluate(
                rule, List.of(sample(89, Quality.UNCERTAIN)), List.of(),
                QualityEvaluationCoverage.none());

        assertThat(cleared.outcome()).isEqualTo(AlarmEvaluation.Outcome.CLEAR);
        assertThat(cleared.evidence().observedValue()).isEqualByComparingTo("89.0");
    }

    @Test
    void 缺失属性和不可用质量不得被解释为恢复() {
        assertThat(evaluator.evaluate(
                numericRule(), List.of(), List.of(), QualityEvaluationCoverage.none()).outcome())
                .isEqualTo(AlarmEvaluation.Outcome.UNKNOWN);
        assertThat(evaluator.evaluate(
                numericRule(), List.of(sample(80, Quality.BAD)), List.of(),
                QualityEvaluationCoverage.none()).outcome())
                .isEqualTo(AlarmEvaluation.Outcome.UNKNOWN);
        assertThat(evaluator.evaluate(
                numericRule(), List.of(sample(80, Quality.TIME_UNTRUSTED)), List.of(),
                QualityEvaluationCoverage.none())
                .outcome()).isEqualTo(AlarmEvaluation.Outcome.UNKNOWN);
    }

    @Test
    void 质量规则同帧多个证据只算一次且无策略时保持未知() {
        AlarmPolicy.Rule rule = qualityRule();
        QualityEvidence matching = evidence("engine.rpm", QualityRuleCode.REQUIRED_MISSING);
        QualityEvidence second = evidence("engine.rpm", QualityRuleCode.REQUIRED_MISSING);
        QualityEvaluationCoverage coverage = QualityEvaluationCoverage.from(
                qualityPolicy(Set.of("engine.rpm"), Map.of()),
                List.of(sample(1200, Quality.GOOD)));

        AlarmEvaluation firing = evaluator.evaluate(
                rule, List.of(sample(1200, Quality.GOOD)), List.of(matching, second), coverage);
        AlarmEvaluation clear = evaluator.evaluate(
                rule, List.of(sample(1200, Quality.GOOD)), List.of(), coverage);
        AlarmEvaluation unavailable = evaluator.evaluate(
                rule, List.of(sample(1200, Quality.GOOD)), List.of(),
                QualityEvaluationCoverage.none());

        assertThat(firing.outcome()).isEqualTo(AlarmEvaluation.Outcome.FIRING);
        assertThat(firing.evidence().qualityRuleCode()).isEqualTo("REQUIRED_MISSING");
        assertThat(clear.outcome()).isEqualTo(AlarmEvaluation.Outcome.CLEAR);
        assertThat(unavailable.outcome()).isEqualTo(AlarmEvaluation.Outcome.UNKNOWN);
    }

    @Test
    void 无关质量策略和缺少目标属性时不得把未命中解释为恢复() {
        QualityEvaluationCoverage unrelated = QualityEvaluationCoverage.from(
                qualityPolicy(Set.of("engine.pressure"), Map.of()),
                List.of(sample(1200, Quality.GOOD)));
        AlarmPolicy.Rule belowMinimum = new AlarmPolicy.Rule(
                "rpm-below-minimum", "转速低于质量下限",
                AlarmPolicy.SourceType.QUALITY, AlarmPolicy.Severity.WARNING,
                0, 0, 60, true, "engine.rpm",
                null, null, null, QualityRuleCode.BELOW_MINIMUM);
        QualityEvaluationCoverage missingTarget = QualityEvaluationCoverage.from(
                qualityPolicy(Set.of(), Map.of("engine.rpm", new BigDecimal("800"))),
                List.of(sample(1200, Quality.GOOD)));

        assertThat(evaluator.evaluate(
                qualityRule(), List.of(sample(1200, Quality.GOOD)), List.of(), unrelated).outcome())
                .isEqualTo(AlarmEvaluation.Outcome.UNKNOWN);
        assertThat(evaluator.evaluate(
                belowMinimum, List.of(sample(1200, Quality.GOOD)), List.of(), missingTarget)
                .outcome()).isEqualTo(AlarmEvaluation.Outcome.UNKNOWN);
    }

    private AlarmPolicy.Rule numericRule() {
        return new AlarmPolicy.Rule(
                "temperature-high", "发动机高温",
                AlarmPolicy.SourceType.NUMERIC, AlarmPolicy.Severity.CRITICAL,
                30, 60, 300, true, "engine.temperature",
                AlarmPolicy.Operator.GT, new BigDecimal("95"), new BigDecimal("90"), null);
    }

    private AlarmPolicy.Rule qualityRule() {
        return new AlarmPolicy.Rule(
                "rpm-required", "转速缺失",
                AlarmPolicy.SourceType.QUALITY, AlarmPolicy.Severity.WARNING,
                30, 60, 300, true, "engine.rpm",
                null, null, null, QualityRuleCode.REQUIRED_MISSING);
    }

    private TelemetrySample sample(double value, Quality quality) {
        return new TelemetrySample(
                EVENT_ID, DEVICE_ID, "excavator-v1", "engine.temperature",
                value, quality, "boot-a", 7L, MessageKind.TELEMETRY,
                AT, AT, AT);
    }

    private QualityEvidence evidence(String propertyId, QualityRuleCode code) {
        return new QualityEvidence(
                EVENT_ID, DEVICE_ID, propertyId, code, Quality.BAD,
                null, null, 1, SNAPSHOT_ID, "sha256:" + "a".repeat(64));
    }

    private DataQualityPolicy qualityPolicy(
            Set<String> required,
            Map<String, BigDecimal> minimums
    ) {
        return new DataQualityPolicy(
                SNAPSHOT_ID, 1L, "sha256:" + "a".repeat(64),
                null, null, required, minimums, Map.of());
    }
}
