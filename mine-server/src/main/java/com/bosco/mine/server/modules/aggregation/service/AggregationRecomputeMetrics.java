package com.bosco.mine.server.modules.aggregation.service;

import com.bosco.mine.server.modules.aggregation.repository.AggregationRecomputeRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 暴露显式聚合重算控制面未完成量和最老可认领积压年龄。
 */
@Component
public final class AggregationRecomputeMetrics {

    /**
     * 注册不包含请求、设备或属性标签的低基数控制面指标。
     *
     * @param registry Micrometer 指标注册表
     * @param repository 聚合重算请求仓储
     */
    public AggregationRecomputeMetrics(
            MeterRegistry registry,
            AggregationRecomputeRepository repository
    ) {
        Objects.requireNonNull(registry, "显式重算指标注册表不能为空");
        AggregationRecomputeRepository source = Objects.requireNonNull(
                repository, "显式重算指标仓储不能为空");
        Gauge.builder("mine.server.aggregation.recompute.unfinished", source,
                        value -> value.countUnfinished())
                .description("控制面尚未进入终态的显式聚合重算请求数量")
                .strongReference(true)
                .register(registry);
        Gauge.builder("mine.server.aggregation.recompute.claimable.oldest.age.seconds", source,
                        value -> value.oldestClaimableAgeSeconds())
                .description("当前最老可认领显式聚合重算请求的积压秒数")
                .strongReference(true)
                .register(registry);
    }
}
