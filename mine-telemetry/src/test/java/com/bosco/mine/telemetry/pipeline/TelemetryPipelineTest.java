package com.bosco.mine.telemetry.pipeline;

import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.telemetry.dedup.DeduplicationPolicy;
import com.bosco.mine.telemetry.time.LateDataPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static com.bosco.mine.telemetry.pipeline.TelemetryPipeline.Action.STORE_HISTORY;
import static com.bosco.mine.telemetry.pipeline.TelemetryPipeline.Action.UPDATE_LATEST;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验弱网场景下遥测消息的去重、乱序和时间判定规则。
 */
class TelemetryPipelineTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-15T08:00:10Z");
    private static final UUID MESSAGE_ID = UUID.fromString("01981d83-2c90-7000-8000-000000000001");
    private static final Duration FINGERPRINT_WINDOW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_FUTURE_CLOCK_SKEW = Duration.ofMinutes(2);

    private final TelemetryPipeline pipeline = new TelemetryPipeline(
            new DeduplicationPolicy(FINGERPRINT_WINDOW),
            new LateDataPolicy(MAXIMUM_FUTURE_CLOCK_SKEW)
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("weakNetworkScenarios")
    void 应按弱网场景生成稳定且可解释的处理决策(
            String scenarioName,
            TelemetryPipeline.Frame frame,
            TelemetryPipeline.Context context,
            DeduplicationPolicy.Outcome expectedOutcome,
            LateDataPolicy.TimeQuality expectedTimeQuality,
            boolean expectedStoreHistory,
            boolean expectedUpdateLatest,
            long expectedSequenceGap) {

        TelemetryPipeline.Decision decision = pipeline.decide(frame, context);

        assertThat(decision.deduplicationOutcome()).isEqualTo(expectedOutcome);
        assertThat(decision.timeQuality()).isEqualTo(expectedTimeQuality);
        assertThat(decision.hasAction(STORE_HISTORY)).isEqualTo(expectedStoreHistory);
        assertThat(decision.hasAction(UPDATE_LATEST)).isEqualTo(expectedUpdateLatest);
        assertThat(decision.sequenceGap()).isEqualTo(expectedSequenceGap);
    }

    @Test
    void 迟到补传应写入历史但不得覆盖最新值() {
        TelemetryPipeline.Frame frame = frame("boot-a", 101L, RECEIVED_AT.minus(Duration.ofMinutes(30)));
        TelemetryPipeline.Context context = context(
                false,
                "boot-a",
                100L,
                null,
                RECEIVED_AT.minus(Duration.ofMinutes(10))
        );

        TelemetryPipeline.Decision decision = pipeline.decide(frame, context);

        assertThat(decision.late()).isTrue();
        assertThat(decision.actions()).containsExactly(STORE_HISTORY);
        assertThat(decision.effectiveAt()).isEqualTo(frame.occurredAt());
    }

    @Test
    void 设备时间不可信时应保留原发生时间并使用平台接收时间排序() {
        Instant deviceOccurredAt = RECEIVED_AT.minus(Duration.ofDays(365));
        TelemetryPipeline.Frame frame = frameWithTimeTrust(
                "boot-a",
                101L,
                deviceOccurredAt,
                false
        );

        TelemetryPipeline.Decision decision = pipeline.decide(
                frame,
                context(false, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(1))
        );

        assertThat(frame.occurredAt()).isEqualTo(deviceOccurredAt);
        assertThat(decision.effectiveAt()).isEqualTo(RECEIVED_AT);
        assertThat(decision.timeQuality()).isEqualTo(LateDataPolicy.TimeQuality.UNTRUSTED);
    }

    private static Stream<Arguments> weakNetworkScenarios() {
        return Stream.of(
                Arguments.of(
                        "相同启动周期和序号重复到达",
                        frame("boot-a", 100L, RECEIVED_AT.minusSeconds(1)),
                        context(false, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(2)),
                        DeduplicationPolicy.Outcome.DUPLICATE,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        false,
                        false,
                        0L
                ),
                Arguments.of(
                        "同一启动周期较小序号乱序到达",
                        frame("boot-a", 99L, RECEIVED_AT.minusSeconds(1)),
                        context(false, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(2)),
                        DeduplicationPolicy.Outcome.OUT_OF_ORDER,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        true,
                        false,
                        0L
                ),
                Arguments.of(
                        "设备重启后序号从头开始",
                        frame("boot-b", 1L, RECEIVED_AT.minusSeconds(1)),
                        context(false, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(2)),
                        DeduplicationPolicy.Outcome.DEVICE_RESTART,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        true,
                        true,
                        0L
                ),
                Arguments.of(
                        "无序列号设备只能有限去重",
                        frame(null, null, RECEIVED_AT.minusSeconds(1)),
                        context(false, null, null, RECEIVED_AT.minus(FINGERPRINT_WINDOW).minusSeconds(1), null),
                        DeduplicationPolicy.Outcome.LIMITED_DEDUPLICATION,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        true,
                        true,
                        0L
                ),
                Arguments.of(
                        "设备时间明显超前时使用接收时间排序",
                        frame("boot-a", 101L, RECEIVED_AT.plus(MAXIMUM_FUTURE_CLOCK_SKEW).plusSeconds(1)),
                        context(false, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(1)),
                        DeduplicationPolicy.Outcome.ACCEPTED,
                        LateDataPolicy.TimeQuality.UNTRUSTED,
                        true,
                        true,
                        0L
                ),
                Arguments.of(
                        "序号跳变时记录缺口",
                        frame("boot-a", 105L, RECEIVED_AT.minusSeconds(1)),
                        context(false, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(2)),
                        DeduplicationPolicy.Outcome.ACCEPTED,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        true,
                        true,
                        4L
                ),
                Arguments.of(
                        "无序列号设备的相同报文在窗口内重复到达",
                        frame(null, null, RECEIVED_AT.minusSeconds(1)),
                        context(false, null, null, RECEIVED_AT.minusSeconds(30), null),
                        DeduplicationPolicy.Outcome.DUPLICATE,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        false,
                        false,
                        0L
                ),
                Arguments.of(
                        "相同消息标识再次投递",
                        frame("boot-a", 101L, RECEIVED_AT.minusSeconds(1)),
                        context(true, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(2)),
                        DeduplicationPolicy.Outcome.DUPLICATE,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        false,
                        false,
                        0L
                ),
                Arguments.of(
                        "已入库乱序序号使用新消息标识重投",
                        frame("boot-a", 99L, RECEIVED_AT.minusSeconds(1)),
                        contextWithBusinessState(
                                false, true, "boot-a", 100L, false,
                                null, RECEIVED_AT.minusSeconds(2)
                        ),
                        DeduplicationPolicy.Outcome.DUPLICATE,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        false,
                        false,
                        0L
                ),
                Arguments.of(
                        "已结束启动周期的报文延迟到达",
                        frame("boot-a", 99L, RECEIVED_AT.minusSeconds(1)),
                        contextWithBusinessState(
                                false, false, "boot-b", 3L, true,
                                null, RECEIVED_AT.minusSeconds(2)
                        ),
                        DeduplicationPolicy.Outcome.STALE_BOOT,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        true,
                        false,
                        0L
                ),
                Arguments.of(
                        "相同指纹较晚接收的副本先被记录",
                        frame(null, null, RECEIVED_AT.minusSeconds(1)),
                        context(false, null, null, RECEIVED_AT.plusSeconds(30), null),
                        DeduplicationPolicy.Outcome.DUPLICATE,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        false,
                        false,
                        0L
                ),
                Arguments.of(
                        "相同发生时间使用更高序号更新最新值",
                        frame("boot-a", 101L, RECEIVED_AT.minusSeconds(1)),
                        context(false, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(1)),
                        DeduplicationPolicy.Outcome.ACCEPTED,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        true,
                        true,
                        0L
                ),
                Arguments.of(
                        "指纹时间差恰好等于窗口仍判定重复",
                        frame(null, null, RECEIVED_AT.minusSeconds(1)),
                        context(false, null, null, RECEIVED_AT.minus(FINGERPRINT_WINDOW), null),
                        DeduplicationPolicy.Outcome.DUPLICATE,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        false,
                        false,
                        0L
                ),
                Arguments.of(
                        "设备时间超前量恰好等于阈值仍视为可信",
                        frame("boot-a", 101L, RECEIVED_AT.plus(MAXIMUM_FUTURE_CLOCK_SKEW)),
                        context(false, "boot-a", 100L, null, RECEIVED_AT.minusSeconds(1)),
                        DeduplicationPolicy.Outcome.ACCEPTED,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        true,
                        true,
                        0L
                ),
                Arguments.of(
                        "没有活动启动周期时已结束周期仍不得重新生效",
                        frame("boot-a", 99L, RECEIVED_AT.minusSeconds(1)),
                        contextWithBusinessState(
                                false, false, null, null, true,
                                null, null
                        ),
                        DeduplicationPolicy.Outcome.STALE_BOOT,
                        LateDataPolicy.TimeQuality.TRUSTED,
                        true,
                        false,
                        0L
                )
        );
    }

    private static TelemetryPipeline.Frame frame(String bootId, Long sequence, Instant occurredAt) {
        return frameWithTimeTrust(bootId, sequence, occurredAt, true);
    }

    private static TelemetryPipeline.Frame frameWithTimeTrust(
            String bootId,
            Long sequence,
            Instant occurredAt,
            boolean deviceTimeTrusted) {
        return new TelemetryPipeline.Frame(
                MESSAGE_ID,
                "haul-truck-01",
                MessageKind.TELEMETRY,
                bootId,
                sequence,
                occurredAt,
                RECEIVED_AT,
                "sha256:payload-01",
                deviceTimeTrusted
        );
    }

    private static TelemetryPipeline.Context context(
            boolean messageIdProcessed,
            String currentBootId,
            Long highestSequence,
            Instant matchingFingerprintSeenAt,
            Instant latestEffectiveAt) {
        return new TelemetryPipeline.Context(
                messageIdProcessed,
                false,
                currentBootId,
                highestSequence,
                false,
                matchingFingerprintSeenAt,
                latestEffectiveAt
        );
    }

    private static TelemetryPipeline.Context contextWithBusinessState(
            boolean messageIdProcessed,
            boolean idempotencyKeyProcessed,
            String currentBootId,
            Long highestSequence,
            boolean candidateBootRetired,
            Instant matchingFingerprintSeenAt,
            Instant latestEffectiveAt) {
        return new TelemetryPipeline.Context(
                messageIdProcessed,
                idempotencyKeyProcessed,
                currentBootId,
                highestSequence,
                candidateBootRetired,
                matchingFingerprintSeenAt,
                latestEffectiveAt
        );
    }
}
