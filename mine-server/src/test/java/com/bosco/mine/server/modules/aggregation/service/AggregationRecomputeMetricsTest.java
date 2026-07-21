package com.bosco.mine.server.modules.aggregation.service;

import com.bosco.mine.server.modules.aggregation.repository.AggregationRecomputeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验显式重算控制面指标不使用高基数标签并读取实时积压事实。
 */
class AggregationRecomputeMetricsTest {

    @Test
    void 应暴露未完成数量和最老可认领积压年龄() {
        AggregationRecomputeRepository repository = mock(AggregationRecomputeRepository.class);
        when(repository.countUnfinished()).thenReturn(3L);
        when(repository.oldestClaimableAgeSeconds()).thenReturn(721L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new AggregationRecomputeMetrics(registry, repository);

        assertThat(registry.get("mine.server.aggregation.recompute.unfinished")
                .gauge().value()).isEqualTo(3D);
        assertThat(registry.get("mine.server.aggregation.recompute.claimable.oldest.age.seconds")
                .gauge().value()).isEqualTo(721D);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).isEmpty());
    }
}
