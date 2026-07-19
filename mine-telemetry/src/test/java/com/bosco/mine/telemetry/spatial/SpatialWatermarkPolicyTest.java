package com.bosco.mine.telemetry.spatial;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证空间事件时间水位、迟到边界和空闲刷新策略。
 */
class SpatialWatermarkPolicyTest {

    private final SpatialWatermarkPolicy policy = new SpatialWatermarkPolicy(
            Duration.ofSeconds(5), Duration.ofSeconds(30));

    @Test
    void 容忍窗内乱序点应保留且边界点可处理() {
        SpatialWatermarkPolicy.Watermark watermark = new SpatialWatermarkPolicy.Watermark(
                Instant.parse("2026-07-19T10:00:20Z"), 20,
                Instant.parse("2026-07-19T10:00:21Z"), null, null);

        assertThat(policy.isTooLate(
                watermark, Instant.parse("2026-07-19T10:00:16Z"), 21)).isFalse();
        assertThat(policy.isTooLate(
                watermark, Instant.parse("2026-07-19T10:00:15Z"), 22)).isFalse();
        assertThat(policy.isTooLate(
                watermark, Instant.parse("2026-07-19T10:00:14.999Z"), 23)).isTrue();
        assertThat(policy.processingCutoff(watermark, Instant.parse("2026-07-19T10:00:49Z")))
                .isEqualTo(Instant.parse("2026-07-19T10:00:15Z"));
    }

    @Test
    void 流量空闲达到阈值后应把水位推进到最大事件时间() {
        SpatialWatermarkPolicy.Watermark watermark = new SpatialWatermarkPolicy.Watermark(
                Instant.parse("2026-07-19T10:00:20Z"), 20,
                Instant.parse("2026-07-19T10:00:21Z"), null, null);

        assertThat(policy.processingCutoff(watermark, Instant.parse("2026-07-19T10:00:51Z")))
                .isEqualTo(Instant.parse("2026-07-19T10:00:20Z"));
    }

    @Test
    void 新观测应只单调推进最大事件时间和摄入序号() {
        SpatialWatermarkPolicy.Watermark current = new SpatialWatermarkPolicy.Watermark(
                Instant.parse("2026-07-19T10:00:20Z"), 20,
                Instant.parse("2026-07-19T10:00:21Z"),
                Instant.parse("2026-07-19T10:00:17Z"), 17L);

        SpatialWatermarkPolicy.Watermark observed = policy.observe(
                current, Instant.parse("2026-07-19T10:00:18Z"), 21,
                Instant.parse("2026-07-19T10:00:22Z"));

        assertThat(observed.maxOccurredAt()).isEqualTo(current.maxOccurredAt());
        assertThat(observed.maxIngestSequence()).isEqualTo(21);
        assertThat(observed.lastArrivalAt()).isEqualTo(Instant.parse("2026-07-19T10:00:22Z"));
        assertThat(observed.finalizedOccurredAt())
                .isEqualTo(Instant.parse("2026-07-19T10:00:17Z"));
    }

    @Test
    void 已最终处理前沿之前的点即使位于普通容忍窗内也应过晚() {
        SpatialWatermarkPolicy.Watermark watermark = new SpatialWatermarkPolicy.Watermark(
                Instant.parse("2026-07-19T10:00:20Z"), 20,
                Instant.parse("2026-07-19T10:00:51Z"),
                Instant.parse("2026-07-19T10:00:20Z"), 20L);

        assertThat(policy.isTooLate(
                watermark, Instant.parse("2026-07-19T10:00:19Z"), 21)).isTrue();
        assertThat(policy.isTooLate(
                watermark, Instant.parse("2026-07-19T10:00:20Z"), 21)).isFalse();
    }
}
