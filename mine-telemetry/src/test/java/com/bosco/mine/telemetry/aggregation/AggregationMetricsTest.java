package com.bosco.mine.telemetry.aggregation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验数值聚合指标只使用固定低基数标签并准确记录稳定结果。
 */
class AggregationMetricsTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void 应记录固定状态计数桶产量迟到量和成功水位() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationMetrics metrics = new AggregationMetrics(registry, CLOCK);
        AggregationExecutor.RunResult result = new AggregationExecutor.RunResult(
                UUID.fromString("019f6f00-0000-7000-8000-000000000802"),
                AggregationExecutor.RunStatus.SUCCEEDED,
                2,
                3,
                51,
                12,
                4,
                null
        );

        metrics.executionStarted();
        metrics.recordResult(result);
        metrics.executionStopped();

        assertThat(registry.get("mine.telemetry.aggregation.runs")
                .tag("status", "SUCCEEDED").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.aggregation.buckets.written")
                .counter().count()).isEqualTo(12D);
        assertThat(registry.get("mine.telemetry.aggregation.late.rows.ignored")
                .counter().count()).isEqualTo(4D);
        assertThat(registry.get("mine.telemetry.aggregation.source.watermark")
                .gauge().value()).isEqualTo(51D);
        assertThat(registry.get("mine.telemetry.aggregation.running").gauge().value()).isZero();
        assertThat(registry.get("mine.telemetry.aggregation.last.success.epoch.seconds")
                .gauge().value()).isEqualTo((double) NOW.getEpochSecond());
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag ->
                        tag.getKey().matches(
                                "device(Id)?|product(Id)?|property(Id)?|execution(Id)?|instance(Id)?")));
    }

    @Test
    void 锁忙不应改变连续失败状态而未收敛异常应计为失败() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationMetrics metrics = new AggregationMetrics(registry, CLOCK);
        metrics.recordUnhandledFailure();
        metrics.recordResult(new AggregationExecutor.RunResult(
                null,
                AggregationExecutor.RunStatus.SKIPPED_LOCKED,
                0, 0, 0, 0, 0, null));

        assertThat(registry.get("mine.telemetry.aggregation.runs")
                .tag("status", "FAILED").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.aggregation.runs")
                .tag("status", "SKIPPED_LOCKED").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.aggregation.consecutive.failures")
                .gauge().value()).isOne();
    }

    @Test
    void 显式重算指标应只使用固定状态且准确暴露启用运行和失败状态() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationMetrics metrics = new AggregationMetrics(registry, CLOCK);

        metrics.recomputeEnabled();
        metrics.recomputeStarted();
        metrics.recomputePollSucceeded();
        metrics.recomputeClaimed(NOW.plusSeconds(600));
        metrics.recordRecomputeUnhandledFailure();
        metrics.recomputeStopped();

        assertThat(registry.get("mine.telemetry.aggregation.recompute.enabled")
                .gauge().value()).isOne();
        assertThat(registry.get("mine.telemetry.aggregation.recompute.running")
                .gauge().value()).isZero();
        assertThat(registry.get("mine.telemetry.aggregation.recompute.runs")
                .tag("status", "UNHANDLED").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.aggregation.recompute.consecutive.failures")
                .gauge().value()).isOne();
        assertThat(registry.get("mine.telemetry.aggregation.recompute.last.poll.epoch.seconds")
                .gauge().value()).isEqualTo((double) NOW.getEpochSecond());
        assertThat(registry.get("mine.telemetry.aggregation.recompute.lease.until.epoch.seconds")
                .gauge().value()).isEqualTo((double) NOW.plusSeconds(600).getEpochSecond());
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag ->
                        tag.getKey().matches(
                                "device(Id)?|product(Id)?|property(Id)?|request(Id)?|execution(Id)?|instance(Id)?")));
    }
}
