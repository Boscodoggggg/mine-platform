package com.bosco.mine.telemetry.location;

import com.bosco.mine.model.location.LocationObservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocationQualityEvaluatorTest {

    private final LocationQualityEvaluator evaluator = new LocationQualityEvaluator(
            Duration.ofMinutes(5), Duration.ofMinutes(1), 30D);

    @Test
    void 应按固定优先级判定时间与精度质量() {
        assertThat(evaluator.evaluate(observation(
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T01:00:01Z"), 2D)))
                .isEqualTo(LocationQualityEvaluator.Evaluation.good());

        assertThat(evaluator.evaluate(observation(
                Instant.parse("2026-07-18T00:50:00Z"),
                Instant.parse("2026-07-18T01:00:00Z"), 2D)).reason())
                .isEqualTo("STALE_OBSERVATION");
        assertThat(evaluator.evaluate(observation(
                Instant.parse("2026-07-18T01:02:00Z"),
                Instant.parse("2026-07-18T01:00:00Z"), 2D)).reason())
                .isEqualTo("CLOCK_AHEAD");
        assertThat(evaluator.evaluate(observation(
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T01:00:01Z"), 31D)).reason())
                .isEqualTo("LOW_ACCURACY");
    }

    private LocationObservation observation(
            Instant occurredAt,
            Instant receivedAt,
            Double accuracy
    ) {
        return new LocationObservation(
                uuid(1), uuid(2), "tag-01", "source-mm",
                1000D, 2000D, 300D, 5D, 180D, accuracy,
                "boot-a", 1L, occurredAt, receivedAt);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7300-0000-7000-8000-%012d".formatted(suffix));
    }
}
