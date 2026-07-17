package com.bosco.mine.telemetry.aggregation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验显式重算工作器每轮最多认领一次并安全处理成功、失败、锁忙和租约丢失。
 */
class AggregationRecomputeWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration LEASE = Duration.ofMinutes(10);

    @Test
    void 无任务时应结束本轮且不调用执行器() {
        AggregationRecomputeClient client = mock(AggregationRecomputeClient.class);
        AggregationRecomputeExecutor executor = mock(AggregationRecomputeExecutor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(client.claim("telemetry-a", LEASE)).thenReturn(Optional.empty());

        AggregationRecomputeWorker.WorkResult result =
                worker(client, executor, registry).runOnce();

        assertThat(result.status()).isEqualTo(AggregationRecomputeWorker.WorkStatus.NO_WORK);
        verify(executor, never()).run(any());
        assertThat(registry.get("mine.telemetry.aggregation.recompute.runs")
                .tag("status", "NO_WORK").counter().count()).isOne();
    }

    @Test
    void 成功执行应使用当前租约令牌完成控制面请求() {
        AggregationRecomputeClient client = mock(AggregationRecomputeClient.class);
        AggregationRecomputeExecutor executor = mock(AggregationRecomputeExecutor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationRecomputeClient.Claim claim = claim();
        when(client.claim("telemetry-a", LEASE)).thenReturn(Optional.of(claim));
        when(executor.run(any())).thenReturn(runResult(
                AggregationRecomputeExecutor.RunStatus.SUCCEEDED, null, 3));
        when(client.complete(
                claim, AggregationRecomputeClient.CompletionStatus.SUCCEEDED, null))
                .thenReturn(AggregationRecomputeClient.CompletionResult.COMPLETED);

        AggregationRecomputeWorker.WorkResult result =
                worker(client, executor, registry).runOnce();

        assertThat(result.status()).isEqualTo(AggregationRecomputeWorker.WorkStatus.SUCCEEDED);
        assertThat(result.bucketCount()).isEqualTo(3);
        verify(client).complete(
                claim, AggregationRecomputeClient.CompletionStatus.SUCCEEDED, null);
        assertThat(registry.get("mine.telemetry.aggregation.recompute.buckets.written")
                .counter().count()).isEqualTo(3D);
    }

    @Test
    void 失败执行应回写脱敏错误而租约丢失应等待稳定重放() {
        AggregationRecomputeClient client = mock(AggregationRecomputeClient.class);
        AggregationRecomputeExecutor executor = mock(AggregationRecomputeExecutor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationRecomputeClient.Claim claim = claim();
        when(client.claim("telemetry-a", LEASE)).thenReturn(Optional.of(claim));
        when(executor.run(any())).thenReturn(runResult(
                AggregationRecomputeExecutor.RunStatus.FAILED,
                "token=[REDACTED] query failed", 1));
        when(client.complete(
                eq(claim), eq(AggregationRecomputeClient.CompletionStatus.FAILED), any()))
                .thenReturn(AggregationRecomputeClient.CompletionResult.LEASE_LOST);

        AggregationRecomputeWorker.WorkResult result =
                worker(client, executor, registry).runOnce();

        assertThat(result.status()).isEqualTo(AggregationRecomputeWorker.WorkStatus.LEASE_LOST);
        verify(client).complete(
                claim, AggregationRecomputeClient.CompletionStatus.FAILED,
                "token=[REDACTED] query failed");
        assertThat(registry.get("mine.telemetry.aggregation.recompute.runs")
                .tag("status", "LEASE_LOST").counter().count()).isOne();
    }

    @Test
    void 锁忙时不得提前终结控制面租约() {
        AggregationRecomputeClient client = mock(AggregationRecomputeClient.class);
        AggregationRecomputeExecutor executor = mock(AggregationRecomputeExecutor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationRecomputeClient.Claim claim = claim();
        when(client.claim("telemetry-a", LEASE)).thenReturn(Optional.of(claim));
        when(executor.run(any())).thenReturn(runResult(
                AggregationRecomputeExecutor.RunStatus.SKIPPED_LOCKED, null, 0));

        AggregationRecomputeWorker.WorkResult result =
                worker(client, executor, registry).runOnce();

        assertThat(result.status())
                .isEqualTo(AggregationRecomputeWorker.WorkStatus.SKIPPED_LOCKED);
        verify(client, never()).complete(any(), any(), any());
    }

    @Test
    void 执行器未收敛异常也应尝试失败回写且不得泄漏敏感值() {
        AggregationRecomputeClient client = mock(AggregationRecomputeClient.class);
        AggregationRecomputeExecutor executor = mock(AggregationRecomputeExecutor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationRecomputeClient.Claim claim = claim();
        when(client.claim("telemetry-a", LEASE)).thenReturn(Optional.of(claim));
        when(executor.run(any())).thenThrow(new IllegalStateException(
                "password=mine-secret\nquery failed"));
        when(client.complete(
                eq(claim), eq(AggregationRecomputeClient.CompletionStatus.FAILED), any()))
                .thenReturn(AggregationRecomputeClient.CompletionResult.COMPLETED);

        AggregationRecomputeWorker.WorkResult result =
                worker(client, executor, registry).runOnce();

        assertThat(result.status()).isEqualTo(AggregationRecomputeWorker.WorkStatus.FAILED);
        verify(client).complete(
                claim, AggregationRecomputeClient.CompletionStatus.FAILED,
                "password=[REDACTED] query failed");
    }

    private AggregationRecomputeWorker worker(
            AggregationRecomputeClient client,
            AggregationRecomputeExecutor executor,
            SimpleMeterRegistry registry
    ) {
        return new AggregationRecomputeWorker(
                client, executor, new AggregationMetrics(registry, CLOCK),
                "telemetry-a", LEASE);
    }

    private AggregationRecomputeClient.Claim claim() {
        return new AggregationRecomputeClient.Claim(
                uuid(881), uuid(882), uuid(883), uuid(884), "engine.rpm",
                Duration.ofMinutes(5), NOW.minusSeconds(600), NOW.minusSeconds(300),
                NOW.plus(LEASE), 1);
    }

    private AggregationRecomputeExecutor.RunResult runResult(
            AggregationRecomputeExecutor.RunStatus status,
            String error,
            int buckets
    ) {
        return new AggregationRecomputeExecutor.RunResult(
                uuid(881), uuid(882), status, 55, buckets, error);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f6f00-0000-7000-8000-%012d".formatted(suffix));
    }
}
