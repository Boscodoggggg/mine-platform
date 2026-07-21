package com.bosco.mine.telemetry.retention;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验数据保留任务使用固定延迟单线程入口，并在异常后保留后续调度能力。
 */
class RetentionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofMinutes(5);

    @Test
    void 应按固定延迟启动并在关闭时取消后续执行() throws Exception {
        RetentionExecutor executor = mock(RetentionExecutor.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), eq(INTERVAL));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetentionMetrics metrics = new RetentionMetrics(registry, CLOCK);
        RetentionScheduler scheduler = new RetentionScheduler(
                executor, metrics, taskScheduler, INITIAL_DELAY, INTERVAL, CLOCK);
        RetentionExecutor.RunResult result = result(
                RetentionExecutor.RunStatus.SUCCEEDED, 12, 7, 2);
        when(executor.runOnce()).thenReturn(result);

        scheduler.run(null);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleWithFixedDelay(
                task.capture(), eq(NOW.plus(INITIAL_DELAY)), eq(INTERVAL));
        task.getValue().run();
        scheduler.close();

        verify(executor).runOnce();
        verify(future).cancel(false);
        assertThat(registry.get("mine.telemetry.retention.runs")
                .tag("status", "SUCCEEDED").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.retention.rows.deleted")
                .tag("dataset", "raw").counter().count()).isEqualTo(12.0d);
        assertThat(registry.get("mine.telemetry.retention.rows.deleted")
                .tag("dataset", "quality").counter().count()).isEqualTo(7.0d);
        assertThat(registry.get("mine.telemetry.retention.running").gauge().value()).isZero();
        assertThat(registry.get("mine.telemetry.retention.aggregation.coverage.blocked.rows")
                .gauge().value()).isEqualTo(5.0d);
        assertThat(registry.get("mine.telemetry.retention.last.success.epoch.seconds")
                .gauge().value()).isEqualTo((double) NOW.getEpochSecond());
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag ->
                        tag.getKey().matches("device(Id)?|product(Id)?|execution(Id)?|instance(Id)?")));
    }

    @Test
    void 单轮异常不得终止后续固定延迟调度() throws Exception {
        RetentionExecutor executor = mock(RetentionExecutor.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), eq(INTERVAL));
        when(executor.runOnce())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(result(RetentionExecutor.RunStatus.SUCCEEDED, 0, 0, 0));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetentionMetrics metrics = new RetentionMetrics(registry, CLOCK);
        RetentionScheduler scheduler = new RetentionScheduler(
                executor, metrics, taskScheduler, INITIAL_DELAY, INTERVAL, CLOCK);

        scheduler.run(null);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleWithFixedDelay(
                task.capture(), eq(NOW.plus(INITIAL_DELAY)), eq(INTERVAL));
        task.getValue().run();
        task.getValue().run();

        verify(executor, times(2)).runOnce();
        assertThat(registry.get("mine.telemetry.retention.runs")
                .tag("status", "FAILED").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.retention.runs")
                .tag("status", "SUCCEEDED").counter().count()).isOne();
        assertThat(registry.get("mine.telemetry.retention.consecutive.failures")
                .gauge().value()).isZero();
    }

    private RetentionExecutor.RunResult result(
            RetentionExecutor.RunStatus status,
            long rawRows,
            long qualityRows,
            int batches
    ) {
        return new RetentionExecutor.RunResult(
                UUID.fromString("019f6e00-0000-7000-8000-000000000701"),
                status, 2, 5, rawRows, qualityRows, batches, null);
    }
}
