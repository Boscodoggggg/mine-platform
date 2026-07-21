package com.bosco.mine.telemetry.location;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证定位存储、转换、Redis 与隔离区指标保持低基数。
 */
class LocationPipelineMetricsTest {

    @Test
    void 应暴露固定结果算法和隔离区大小且不包含业务身份标签() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocationPipelineMetrics metrics = new LocationPipelineMetrics(registry, () -> 12L);

        metrics.ingest(LocationPipelineMetrics.IngestResult.STORED,
                LocationPipelineMetrics.IngestReason.NONE);
        metrics.ingest(LocationPipelineMetrics.IngestResult.QUARANTINED,
                LocationPipelineMetrics.IngestReason.UNKNOWN_TAG);
        metrics.transform(LocationPipelineMetrics.TransformAlgorithm.AFFINE_3D,
                LocationPipelineMetrics.TransformResult.SUCCESS);
        metrics.redisLatest(LocationPipelineMetrics.RedisResult.UPDATED);
        metrics.redisLatest(LocationPipelineMetrics.RedisResult.FAILURE);

        assertThat(registry.get("mine.telemetry.location.ingest")
                .tags("result", "stored", "reason", "none")
                .counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.location.transform")
                .tags("algorithm", "affine_3d", "result", "success")
                .counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.location.redis.latest")
                .tag("result", "failure").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.location.quarantine.size")
                .gauge().value()).isEqualTo(12D);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn(Set.of(
                                "result", "reason", "algorithm"))));
    }
}
