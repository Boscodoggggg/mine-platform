package com.bosco.mine.telemetry.spatial;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 验证空间事件运行指标只暴露固定阶段、结果、原因、区域和事件类型标签。
 */
class SpatialEventMetricsTest {

    @Test
    void 应暴露队列积压死信重放年龄和固定低基数处理结果() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger backlog = new AtomicInteger(12);
        AtomicInteger outboxDead = new AtomicInteger(2);
        AtomicInteger replayAge = new AtomicInteger(901);
        SpatialEventMetrics metrics = new SpatialEventMetrics(
                registry, backlog::doubleValue, outboxDead::doubleValue,
                replayAge::doubleValue);

        metrics.queue(SpatialEventMetrics.Result.SUCCESS, SpatialEventMetrics.Reason.NONE);
        metrics.queue(SpatialEventMetrics.Result.SKIPPED, SpatialEventMetrics.Reason.TOO_LATE);
        metrics.stage(SpatialEventMetrics.Stage.EVALUATION,
                SpatialEventMetrics.Result.FAILURE,
                SpatialEventMetrics.Reason.STATE_RECOVERY);
        metrics.stage(SpatialEventMetrics.Stage.OUTBOX,
                SpatialEventMetrics.Result.DEAD,
                SpatialEventMetrics.Reason.RETRY_EXHAUSTED);
        metrics.event(SpatialEventMetrics.ZoneType.GEOFENCE,
                SpatialEventMetrics.EventType.ENTER,
                SpatialEventMetrics.Result.SUCCESS);

        assertThat(registry.get("mine.telemetry.spatial.queue.events")
                .tags("result", "skipped", "reason", "too_late")
                .counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.spatial.stage.events")
                .tags("stage", "evaluation", "result", "failure",
                        "reason", "state_recovery")
                .counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.spatial.events")
                .tags("zoneType", "geofence", "eventType", "enter",
                        "result", "success")
                .counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.spatial.queue.backlog")
                .gauge().value()).isEqualTo(12D);
        assertThat(registry.get("mine.telemetry.spatial.outbox.dead")
                .gauge().value()).isEqualTo(2D);
        assertThat(registry.get("mine.telemetry.spatial.replay.oldest.active.age.seconds")
                .gauge().value()).isEqualTo(901D);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn(Set.of(
                                "result", "reason", "zoneType", "eventType", "stage"))));
    }

    @Test
    void 指标注册或更新失败不得中断空间业务处理() {
        SimpleMeterRegistry failingRegistry = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("forced counter failure");
            }

            @Override
            protected <T> Gauge newGauge(Meter.Id id, T object, ToDoubleFunction<T> valueFunction) {
                throw new IllegalStateException("forced gauge failure");
            }
        };

        SpatialEventMetrics[] metrics = new SpatialEventMetrics[1];
        assertThatCode(() -> metrics[0] = new SpatialEventMetrics(
                failingRegistry, () -> 0D, () -> 0D, () -> 0D))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].queue(
                SpatialEventMetrics.Result.SUCCESS, SpatialEventMetrics.Reason.NONE))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].stage(
                SpatialEventMetrics.Stage.OUTBOX,
                SpatialEventMetrics.Result.FAILURE,
                SpatialEventMetrics.Reason.PUBLISH_FAILED))
                .doesNotThrowAnyException();
        assertThatCode(() -> metrics[0].event(
                SpatialEventMetrics.ZoneType.GEOFENCE,
                SpatialEventMetrics.EventType.ENTER,
                SpatialEventMetrics.Result.SUCCESS))
                .doesNotThrowAnyException();
    }
}
