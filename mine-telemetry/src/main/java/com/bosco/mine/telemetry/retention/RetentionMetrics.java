package com.bosco.mine.telemetry.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 只使用固定状态和数据集标签记录数据保留运行健康度。
 */
public final class RetentionMetrics {

    private final Clock clock;
    private final Map<RetentionExecutor.RunStatus, Counter> runs;
    private final Counter rawRowsDeleted;
    private final Counter qualityRowsDeleted;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger consecutivePartial = new AtomicInteger();
    private final AtomicInteger policyCount = new AtomicInteger();
    private final AtomicLong startedEpochSeconds = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicLong lastCompletionEpochSeconds = new AtomicLong();

    /**
     * 使用 UTC 系统时钟注册数据保留低基数指标。
     *
     * @param registry Micrometer 指标注册表
     */
    public RetentionMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    /** 创建可注入确定性时钟的指标测试构造器。 */
    RetentionMetrics(MeterRegistry registry, Clock clock) {
        Objects.requireNonNull(registry, "数据保留指标注册表不能为空");
        this.clock = Objects.requireNonNull(clock, "数据保留指标时钟不能为空");
        this.startedEpochSeconds.set(clock.instant().getEpochSecond());
        this.runs = registerRunCounters(registry);
        this.rawRowsDeleted = Counter.builder("mine.telemetry.retention.rows.deleted")
                .description("数据保留已经提交删除的行数")
                .tag("dataset", "raw")
                .register(registry);
        this.qualityRowsDeleted = Counter.builder("mine.telemetry.retention.rows.deleted")
                .description("数据保留已经提交删除的行数")
                .tag("dataset", "quality")
                .register(registry);
        registerGauge(registry, "mine.telemetry.retention.running",
                "当前实例是否正在执行数据保留任务", running);
        registerGauge(registry, "mine.telemetry.retention.consecutive.failures",
                "当前实例连续失败的数据保留轮次数", consecutiveFailures);
        registerGauge(registry, "mine.telemetry.retention.consecutive.partial",
                "当前实例连续部分完成的数据保留轮次数", consecutivePartial);
        registerGauge(registry, "mine.telemetry.retention.policy.count",
                "最近一轮观察到的启用数据保留策略数", policyCount);
        registerGauge(registry, "mine.telemetry.retention.started.epoch.seconds",
                "当前实例数据保留指标初始化时间", startedEpochSeconds);
        registerGauge(registry, "mine.telemetry.retention.last.success.epoch.seconds",
                "最近完整扫描成功的当前实例 JVM 观测时间戳秒数", lastSuccessEpochSeconds);
        registerGauge(registry, "mine.telemetry.retention.last.completion.epoch.seconds",
                "最近一轮调度完成的当前实例 JVM 观测时间戳秒数", lastCompletionEpochSeconds);
    }

    /** 标记调度线程已经进入一轮数据保留执行。 */
    public void executionStarted() {
        running.set(1);
    }

    /**
     * 记录执行器返回的稳定结果和已经提交的删除量。
     *
     * @param result 单轮数据保留结果
     */
    public void recordResult(RetentionExecutor.RunResult result) {
        Objects.requireNonNull(result, "数据保留运行结果不能为空");
        runs.get(result.status()).increment();
        if (result.rawRowsDeleted() > 0) {
            rawRowsDeleted.increment(result.rawRowsDeleted());
        }
        if (result.qualityRowsDeleted() > 0) {
            qualityRowsDeleted.increment(result.qualityRowsDeleted());
        }
        policyCount.set(result.policyCount());
        lastCompletionEpochSeconds.set(clock.instant().getEpochSecond());
        switch (result.status()) {
            case SUCCEEDED -> {
                consecutiveFailures.set(0);
                consecutivePartial.set(0);
                lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
            }
            case PARTIAL -> {
                consecutiveFailures.set(0);
                consecutivePartial.incrementAndGet();
            }
            case FAILED -> {
                consecutiveFailures.incrementAndGet();
                consecutivePartial.set(0);
            }
            case SKIPPED_LOCKED -> {
                // 其他实例正常持锁不改变本实例最近成功、失败或未完成连续状态。
            }
        }
    }

    /** 记录执行事实创建前等未能由执行器收敛的基础设施失败。 */
    public void recordUnhandledFailure() {
        runs.get(RetentionExecutor.RunStatus.FAILED).increment();
        consecutiveFailures.incrementAndGet();
        consecutivePartial.set(0);
        lastCompletionEpochSeconds.set(clock.instant().getEpochSecond());
    }

    /** 标记本轮调度已经退出，供运行中停滞告警观察。 */
    public void executionStopped() {
        running.set(0);
    }

    private Map<RetentionExecutor.RunStatus, Counter> registerRunCounters(
            MeterRegistry registry
    ) {
        Map<RetentionExecutor.RunStatus, Counter> counters =
                new EnumMap<>(RetentionExecutor.RunStatus.class);
        for (RetentionExecutor.RunStatus status : RetentionExecutor.RunStatus.values()) {
            counters.put(status, Counter.builder("mine.telemetry.retention.runs")
                    .description("按固定结果统计的数据保留调度轮次")
                    .tag("status", status.name())
                    .register(registry));
        }
        return Map.copyOf(counters);
    }

    private void registerGauge(
            MeterRegistry registry,
            String name,
            String description,
            AtomicInteger value
    ) {
        Gauge.builder(name, value, AtomicInteger::doubleValue)
                .description(description)
                .strongReference(true)
                .register(registry);
    }

    private void registerGauge(
            MeterRegistry registry,
            String name,
            String description,
            AtomicLong value
    ) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description)
                .strongReference(true)
                .register(registry);
    }
}
