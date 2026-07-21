package com.bosco.mine.telemetry.consumer;

import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验设备序列缺口会转换为稳定的低基数监控指标。
 */
class TelemetrySequenceGapMetricsTest {

    @Test
    void 应分别累计缺口事件数和缺失序号数() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetrySequenceGapMetrics metrics = new TelemetrySequenceGapMetrics(registry);

        metrics.observe(new TelemetryBatchWriter.SequenceGap(
                UUID.fromString("019f6572-cc00-7000-8000-000000000007"),
                UUID.fromString("019f6572-cc00-7000-8000-000000000001"),
                "weak-boot-a",
                3L,
                7L,
                3L
        ));

        assertThat(registry.get("mine.telemetry.sequence.gap.events").counter().count())
                .isOne();
        assertThat(registry.get("mine.telemetry.sequence.gap.missing").counter().count())
                .isEqualTo(3D);
    }
}
