package com.bosco.mine.gateway.location;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Gateway 定位接入指标只使用固定结果和原因标签。
 */
class LocationIngestMetricsTest {

    @Test
    void 应记录接受拒绝和重投且禁止业务标识进入标签() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocationIngestMetrics metrics = new LocationIngestMetrics(registry);

        metrics.record(LocationIngestMetrics.Result.ACCEPTED,
                LocationIngestMetrics.Reason.NONE);
        metrics.record(LocationIngestMetrics.Result.REJECTED,
                LocationIngestMetrics.Reason.INVALID_MESSAGE);
        metrics.record(LocationIngestMetrics.Result.REQUEUED,
                LocationIngestMetrics.Reason.PUBLISH_FAILURE);

        assertThat(registry.get("mine.gateway.location.ingest")
                .tags("result", "accepted", "reason", "none")
                .counter().count()).isOne();
        assertThat(registry.get("mine.gateway.location.ingest")
                .tags("result", "rejected", "reason", "invalid_message")
                .counter().count()).isOne();
        assertThat(registry.get("mine.gateway.location.ingest")
                .tags("result", "requeued", "reason", "publish_failure")
                .counter().count()).isOne();
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getKey())
                                .isIn(Set.of("result", "reason"))));
    }
}
