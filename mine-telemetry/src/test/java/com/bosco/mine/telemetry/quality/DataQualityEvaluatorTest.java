package com.bosco.mine.telemetry.quality;

import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验数据质量策略的时间、必填、范围和属性级质量合并语义。
 */
class DataQualityEvaluatorTest {

    private static final UUID EVENT_ID =
            UUID.fromString("019f6e00-0000-7000-8000-000000000101");
    private static final UUID DEVICE_ID =
            UUID.fromString("019f6e00-0000-7000-8000-000000000102");
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("019f6e00-0000-7000-8000-000000000103");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-17T02:00:00Z");

    private final DataQualityEvaluator evaluator = new DataQualityEvaluator();

    @Test
    void 应保留越界属性并生成证据且不污染同帧正常属性() {
        DataQualityPolicy policy = policy(Map.ofEntries(
                Map.entry("required.engine.pressure", true),
                Map.entry("minimum.engine.rpm", 1000),
                Map.entry("maximum.engine.rpm", 2000),
                Map.entry("maximum.engine.temperature", 90)
        ));
        UnifiedDeviceMessage payload = payload(
                RECEIVED_AT.minusSeconds(2),
                Map.of("engine.rpm", 800, "engine.temperature", 83.5),
                Quality.GOOD
        );

        DataQualityEvaluation result = evaluator.evaluate(
                EVENT_ID, DEVICE_ID, payload, policy
        );

        assertThat(result.samples()).hasSize(2);
        assertThat(result.samples())
                .filteredOn(sample -> sample.propertyId().equals("engine.rpm"))
                .singleElement()
                .satisfies(sample -> {
                    assertThat(sample.value()).isEqualTo(800D);
                    assertThat(sample.quality()).isEqualTo(Quality.BAD);
                });
        assertThat(result.samples())
                .filteredOn(sample -> sample.propertyId().equals("engine.temperature"))
                .singleElement()
                .satisfies(sample -> assertThat(sample.quality()).isEqualTo(Quality.GOOD));
        assertThat(result.evidence())
                .extracting(QualityEvidence::ruleCode, QualityEvidence::propertyId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                QualityRuleCode.BELOW_MINIMUM, "engine.rpm"),
                        org.assertj.core.groups.Tuple.tuple(
                                QualityRuleCode.REQUIRED_MISSING, "engine.pressure")
                );
        assertThat(result.evidence())
                .allSatisfy(evidence -> {
                    assertThat(evidence.policyRuntimeRevision()).isEqualTo(7L);
                    assertThat(evidence.policySnapshotId()).isEqualTo(SNAPSHOT_ID);
                    assertThat(evidence.policyContentHash())
                            .isEqualTo("sha256:" + "a".repeat(64));
                });
    }

    @Test
    void 陈旧时间应降级现有属性且范围错误仍合并为Bad() {
        DataQualityPolicy policy = policy(Map.of(
                "staleAfterSeconds", 60,
                "maximum.engine.temperature", 80
        ));
        UnifiedDeviceMessage payload = payload(
                RECEIVED_AT.minusSeconds(61),
                Map.of("engine.rpm", 1200, "engine.temperature", 83.5),
                Quality.GOOD
        );

        DataQualityEvaluation result = evaluator.evaluate(
                EVENT_ID, DEVICE_ID, payload, policy
        );

        assertThat(result.samples())
                .filteredOn(sample -> sample.propertyId().equals("engine.rpm"))
                .singleElement()
                .satisfies(sample -> assertThat(sample.quality()).isEqualTo(Quality.UNCERTAIN));
        assertThat(result.samples())
                .filteredOn(sample -> sample.propertyId().equals("engine.temperature"))
                .singleElement()
                .satisfies(sample -> assertThat(sample.quality()).isEqualTo(Quality.BAD));
        assertThat(result.evidence())
                .extracting(QualityEvidence::ruleCode)
                .containsExactlyInAnyOrder(
                        QualityRuleCode.STALE_TIMESTAMP,
                        QualityRuleCode.STALE_TIMESTAMP,
                        QualityRuleCode.ABOVE_MAXIMUM
                );
        assertThat(result.samples())
                .allSatisfy(sample -> assertThat(sample.effectiveAt())
                        .isEqualTo(payload.occurredAt()));
    }

    @Test
    void 超前时间超过容忍值时应使用接收时间排序并标记时间不可信() {
        DataQualityPolicy policy = policy(Map.of("futureToleranceSeconds", 5));
        UnifiedDeviceMessage payload = payload(
                RECEIVED_AT.plusSeconds(6),
                Map.of("engine.rpm", 1200),
                Quality.GOOD
        );

        DataQualityEvaluation result = evaluator.evaluate(
                EVENT_ID, DEVICE_ID, payload, policy
        );

        assertThat(result.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.quality()).isEqualTo(Quality.TIME_UNTRUSTED);
            assertThat(sample.occurredAt()).isEqualTo(payload.occurredAt());
            assertThat(sample.effectiveAt()).isEqualTo(RECEIVED_AT);
        });
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.ruleCode()).isEqualTo(QualityRuleCode.FUTURE_TIMESTAMP);
            assertThat(evidence.resultQuality()).isEqualTo(Quality.TIME_UNTRUSTED);
            assertThat(evidence.observedValue()).isEqualByComparingTo("6");
            assertThat(evidence.thresholdValue()).isEqualByComparingTo("5");
        });
    }

    @Test
    void 时间与范围边界值应视为合格且保留上游较差质量() {
        DataQualityPolicy policy = policy(Map.ofEntries(
                Map.entry("staleAfterSeconds", 60),
                Map.entry("futureToleranceSeconds", 5),
                Map.entry("minimum.engine.rpm", 1000),
                Map.entry("maximum.engine.rpm", 2000)
        ));
        UnifiedDeviceMessage staleBoundary = payload(
                RECEIVED_AT.minusSeconds(60), Map.of("engine.rpm", 1000), Quality.UNCERTAIN
        );
        UnifiedDeviceMessage futureBoundary = payload(
                RECEIVED_AT.plusSeconds(5), Map.of("engine.rpm", 2000), Quality.GOOD
        );

        DataQualityEvaluation staleResult = evaluator.evaluate(
                EVENT_ID, DEVICE_ID, staleBoundary, policy
        );
        DataQualityEvaluation futureResult = evaluator.evaluate(
                EVENT_ID, DEVICE_ID, futureBoundary, policy
        );

        assertThat(staleResult.evidence()).isEmpty();
        assertThat(staleResult.samples()).singleElement()
                .satisfies(sample -> assertThat(sample.quality()).isEqualTo(Quality.UNCERTAIN));
        assertThat(futureResult.evidence()).isEmpty();
        assertThat(futureResult.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.quality()).isEqualTo(Quality.GOOD);
            assertThat(sample.effectiveAt()).isEqualTo(futureBoundary.occurredAt());
        });
    }

    private DataQualityPolicy policy(Map<String, Object> values) {
        return DataQualityPolicy.from(
                SNAPSHOT_ID, 7L, "sha256:" + "a".repeat(64), values
        );
    }

    private UnifiedDeviceMessage payload(
            Instant occurredAt,
            Map<String, Object> values,
            Quality quality
    ) {
        return new UnifiedDeviceMessage(
                EVENT_ID,
                MessageKind.TELEMETRY,
                "haul-truck",
                DEVICE_ID.toString(),
                "v1",
                "boot-a",
                7L,
                occurredAt,
                RECEIVED_AT,
                false,
                new TelemetryPayload(new LinkedHashMap<>(values)),
                quality,
                null
        );
    }
}
