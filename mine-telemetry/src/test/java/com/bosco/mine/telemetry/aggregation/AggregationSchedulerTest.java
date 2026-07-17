package com.bosco.mine.telemetry.aggregation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验数值聚合使用固定延迟单线程入口，并在单轮异常后保留后续调度能力。
 */
class AggregationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofMinutes(1);

    @Test
    void 应按固定延迟启动执行并在关闭时取消后续轮次() throws Exception {
        AggregationExecutor executor = mock(AggregationExecutor.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), eq(INTERVAL));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationMetrics metrics = new AggregationMetrics(registry, CLOCK);
        AggregationScheduler scheduler = new AggregationScheduler(
                executor, metrics, taskScheduler, INITIAL_DELAY, INTERVAL, CLOCK);
        when(executor.runOnce()).thenReturn(result(AggregationExecutor.RunStatus.SUCCEEDED));

        scheduler.run(null);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleWithFixedDelay(
                task.capture(), eq(NOW.plus(INITIAL_DELAY)), eq(INTERVAL));
        task.getValue().run();
        scheduler.close();

        verify(executor).runOnce();
        verify(future).cancel(false);
        assertThat(registry.get("mine.telemetry.aggregation.runs")
                .tag("status", "SUCCEEDED").counter().count()).isOne();
    }

    @Test
    void 单轮异常不得终止后续固定延迟调度() throws Exception {
        AggregationExecutor executor = mock(AggregationExecutor.class);
        AggregationRecomputeWorker recomputeWorker = mock(AggregationRecomputeWorker.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), eq(INTERVAL));
        when(executor.runOnce())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(result(AggregationExecutor.RunStatus.SUCCEEDED));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationMetrics metrics = new AggregationMetrics(registry, CLOCK);
        AggregationScheduler scheduler = new AggregationScheduler(
                executor, metrics, recomputeWorker,
                taskScheduler, INITIAL_DELAY, INTERVAL, CLOCK);
        when(recomputeWorker.runOnce()).thenReturn(new AggregationRecomputeWorker.WorkResult(
                AggregationRecomputeWorker.WorkStatus.NO_WORK,
                null, null, 0, null));

        scheduler.run(null);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleWithFixedDelay(
                task.capture(), eq(NOW.plus(INITIAL_DELAY)), eq(INTERVAL));
        task.getValue().run();
        task.getValue().run();

        verify(executor, times(2)).runOnce();
        verify(recomputeWorker, times(2)).runOnce();
        assertThat(registry.get("mine.telemetry.aggregation.runs")
                .tag("status", "FAILED").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.aggregation.runs")
                .tag("status", "SUCCEEDED").counter().count()).isOne();
    }

    private AggregationExecutor.RunResult result(AggregationExecutor.RunStatus status) {
        return new AggregationExecutor.RunResult(
                UUID.fromString("019f6f00-0000-7000-8000-000000000803"),
                status, 2, 3, 51, 12, 4, null);
    }
}
