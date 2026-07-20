package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.modules.mine.scene.SceneMetrics;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用有界专用探针检查三维场景资源存储，不影响平台二维运行能力。
 */
@Component("sceneAssetStorage")
public final class SceneAssetStorageHealthIndicator implements HealthIndicator, AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration MONITOR_INTERVAL = Duration.ofSeconds(30);

    private final SceneAssetStorage storage;
    private final SceneMetrics metrics;
    private final Duration timeout;
    private final ExecutorService executor;
    private final ScheduledExecutorService monitor;
    private final AtomicReference<ProbeRound> probeInFlight = new AtomicReference<>();
    private final AtomicBoolean monitorStarted = new AtomicBoolean();

    /**
     * 创建默认两秒超时的三维场景存储健康检查。
     *
     * @param storage 三维资源存储端口
     * @param metrics 三维场景运行指标
     */
    @Autowired
    public SceneAssetStorageHealthIndicator(SceneAssetStorage storage, SceneMetrics metrics) {
        this(storage, metrics, DEFAULT_TIMEOUT);
    }

    /**
     * 创建可注入固定超时的三维场景存储健康检查。
     *
     * @param storage 三维资源存储端口
     * @param metrics 三维场景运行指标
     * @param timeout 单次探针最大等待时间
     */
    public SceneAssetStorageHealthIndicator(
            SceneAssetStorage storage,
            SceneMetrics metrics,
            Duration timeout
    ) {
        this.storage = Objects.requireNonNull(storage, "三维资源存储不能为空");
        this.metrics = Objects.requireNonNull(metrics, "三维场景指标不能为空");
        this.timeout = requireTimeout(timeout);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.monitor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform()
                        .daemon(true)
                        .name("scene-storage-health-monitor")
                        .factory());
    }

    /**
     * 应用完全启动后立即探测一次，并按固定低频周期刷新 Prometheus Gauge。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startMonitoring() {
        if (monitorStarted.compareAndSet(false, true)) {
            monitor.scheduleWithFixedDelay(
                    this::refreshSafely,
                    0,
                    MONITOR_INTERVAL.toSeconds(),
                    TimeUnit.SECONDS);
        }
    }

    /**
     * 有界执行专用创建、读取和删除探针，失败详情只返回固定脱敏代码。
     *
     * @return 三维存储健康状态；二维运行能力始终可继续使用
     */
    @Override
    public Health health() {
        ProbeRound round = currentRound();
        try {
            return health(round.result().get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException exception) {
            ProbeOutcome completed = round.timeout(metrics);
            if (completed != null) {
                return health(completed);
            }
            return base(Health.down()).withDetail("reason", "probe-timeout").build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            round.timeout(metrics);
            return base(Health.down()).withDetail("reason", "probe-interrupted").build();
        } catch (ExecutionException exception) {
            return base(Health.down()).withDetail("reason", "probe-failed").build();
        }
    }

    /**
     * 关闭健康探针虚拟线程执行器。
     */
    @Override
    @PreDestroy
    public void close() {
        monitor.shutdownNow();
        executor.shutdownNow();
    }

    private void refreshSafely() {
        try {
            health();
        } catch (RuntimeException ignored) {
            metrics.storageAvailable(false);
        }
    }

    private ProbeRound currentRound() {
        while (true) {
            ProbeRound current = probeInFlight.get();
            if (current != null) {
                return current;
            }
            ProbeRound created = new ProbeRound();
            if (!probeInFlight.compareAndSet(null, created)) {
                continue;
            }
            try {
                executor.execute(() -> execute(created));
            } catch (RuntimeException exception) {
                created.failure(metrics);
                probeInFlight.compareAndSet(created, null);
            }
            return created;
        }
    }

    private void execute(ProbeRound round) {
        try {
            storage.probe();
            round.success(metrics);
        } catch (Exception exception) {
            round.failure(metrics);
        } finally {
            probeInFlight.compareAndSet(round, null);
        }
    }

    private Health health(ProbeOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> base(Health.up()).build();
            case TIMEOUT -> base(Health.down())
                    .withDetail("reason", "probe-timeout").build();
            case FAILURE -> base(Health.down())
                    .withDetail("reason", "probe-failed").build();
        };
    }

    private Health.Builder base(Health.Builder builder) {
        return builder
                .withDetail("component", "scene-asset-storage")
                .withDetail("fallback", "2d-ready");
    }

    private Duration requireTimeout(Duration value) {
        Duration safe = Objects.requireNonNull(value, "场景存储探针超时不能为空");
        if (safe.isZero() || safe.isNegative() || safe.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("场景存储探针超时必须大于零且不超过三十秒");
        }
        return safe;
    }

    /** 单轮共享探针固定结果。 */
    private enum ProbeOutcome {
        /** 在有界时间内成功。 */
        SUCCESS,
        /** 任一等待方已达到有界超时。 */
        TIMEOUT,
        /** 存储探针执行失败。 */
        FAILURE
    }

    /** 合并并发健康请求并保证指标状态按探针轮次单调提交。 */
    private static final class ProbeRound {

        private final CompletableFuture<ProbeOutcome> result = new CompletableFuture<>();
        private boolean timedOut;

        /** @return 当前轮次共享结果 */
        CompletableFuture<ProbeOutcome> result() {
            return result;
        }

        /** 在尚未超时时提交成功，否则保持超时失败。 */
        synchronized void success(SceneMetrics metrics) {
            if (timedOut) {
                metrics.storageAvailable(false);
                result.complete(ProbeOutcome.TIMEOUT);
            } else {
                metrics.storageAvailable(true);
                result.complete(ProbeOutcome.SUCCESS);
            }
        }

        /** 提交失败结果。 */
        synchronized void failure(SceneMetrics metrics) {
            metrics.storageAvailable(false);
            result.complete(ProbeOutcome.FAILURE);
        }

        /**
         * 标记当前轮次超时；若恰好已完成则返回已完成结果供调用方复用。
         *
         * @return 已完成结果，仍在执行时为空
         */
        synchronized ProbeOutcome timeout(SceneMetrics metrics) {
            ProbeOutcome completed = result.getNow(null);
            if (completed != null) {
                return completed;
            }
            timedOut = true;
            metrics.storageAvailable(false);
            return null;
        }
    }
}
