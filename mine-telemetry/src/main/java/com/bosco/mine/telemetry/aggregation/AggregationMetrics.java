package com.bosco.mine.telemetry.aggregation;

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
 * 只使用固定状态标签记录数值聚合任务的运行健康度和有效产量。
 */
public final class AggregationMetrics {

    private final Clock clock;
    private final Map<AggregationExecutor.RunStatus, Counter> runs;
    private final Counter bucketsWritten;
    private final Counter lateRowsIgnored;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger consecutivePartial = new AtomicInteger();
    private final AtomicInteger policyCount = new AtomicInteger();
    private final AtomicInteger ruleCount = new AtomicInteger();
    private final AtomicLong sourceWatermark = new AtomicLong();
    private final AtomicLong startedEpochSeconds = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicLong lastCompletionEpochSeconds = new AtomicLong();

    /**
     * 使用 UTC 系统时钟注册数值聚合低基数指标。
     *
     * @param registry Micrometer 指标注册表
     */
    public AggregationMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    /**
     * 创建可注入确定性时钟的指标测试实例。
     */
    AggregationMetrics(MeterRegistry registry, Clock clock) {
        Objects.requireNonNull(registry, "数值聚合指标注册表不能为空");
        this.clock = Objects.requireNonNull(clock, "数值聚合指标时钟不能为空");
        this.startedEpochSeconds.set(clock.instant().getEpochSecond());
        this.runs = registerRunCounters(registry);
        this.bucketsWritten = Counter.builder("mine.telemetry.aggregation.buckets.written")
                .description("数值聚合已经提交的版本化桶数量")
                .register(registry);
        this.lateRowsIgnored = Counter.builder("mine.telemetry.aggregation.late.rows.ignored")
                .description("完整成功轮次观察到的超窗口迟到原始遥测行数")
                .register(registry);
        registerGauge(registry, "mine.telemetry.aggregation.running",
                "当前实例是否正在执行数值聚合任务", running);
        registerGauge(registry, "mine.telemetry.aggregation.consecutive.failures",
                "当前实例连续失败的数值聚合轮次数", consecutiveFailures);
        registerGauge(registry, "mine.telemetry.aggregation.consecutive.partial",
                "当前实例连续部分完成的数值聚合轮次数", consecutivePartial);
        registerGauge(registry, "mine.telemetry.aggregation.policy.count",
                "最近一轮观察到的启用设备聚合策略数", policyCount);
        registerGauge(registry, "mine.telemetry.aggregation.rule.count",
                "最近一轮观察到的启用属性桶规则数", ruleCount);
        registerGauge(registry, "mine.telemetry.aggregation.source.watermark",
                "最近一轮固定的原始遥测摄取水位", sourceWatermark);
        registerGauge(registry, "mine.telemetry.aggregation.started.epoch.seconds",
                "当前实例数值聚合指标初始化时间", startedEpochSeconds);
        registerGauge(registry, "mine.telemetry.aggregation.last.success.epoch.seconds",
                "最近完整扫描成功的当前实例 JVM 观测时间戳秒数", lastSuccessEpochSeconds);
        registerGauge(registry, "mine.telemetry.aggregation.last.completion.epoch.seconds",
                "最近一轮调度完成的当前实例 JVM 观测时间戳秒数", lastCompletionEpochSeconds);
    }

    /** 标记调度线程已经进入一轮数值聚合执行。 */
    public void executionStarted() {
        running.set(1);
    }

    /**
     * 记录执行器返回的稳定结果和已经提交的聚合产量。
     *
     * @param result 单轮数值聚合结果
     */
    public void recordResult(AggregationExecutor.RunResult result) {
        Objects.requireNonNull(result, "数值聚合运行结果不能为空");
        runs.get(result.status()).increment();
        if (result.bucketCount() > 0) {
            bucketsWritten.increment(result.bucketCount());
        }
        if (result.status() == AggregationExecutor.RunStatus.SUCCEEDED
                && result.lateRowsIgnored() > 0) {
            lateRowsIgnored.increment(result.lateRowsIgnored());
        }
        policyCount.set(result.policyCount());
        ruleCount.set(result.ruleCount());
        sourceWatermark.set(result.sourceWatermark());
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
                /** 其他实例正常持锁时保留当前实例连续健康状态。 */
            }
        }
    }

    /** 记录执行事实创建前等未能由执行器收敛的基础设施失败。 */
    public void recordUnhandledFailure() {
        runs.get(AggregationExecutor.RunStatus.FAILED).increment();
        consecutiveFailures.incrementAndGet();
        consecutivePartial.set(0);
        lastCompletionEpochSeconds.set(clock.instant().getEpochSecond());
    }

    /** 标记本轮调度已经退出，供运行中停滞告警观察。 */
    public void executionStopped() {
        running.set(0);
    }

    /**
     * 为所有固定运行结果预注册计数器。
     */
    private Map<AggregationExecutor.RunStatus, Counter> registerRunCounters(
            MeterRegistry registry
    ) {
        Map<AggregationExecutor.RunStatus, Counter> counters =
                new EnumMap<>(AggregationExecutor.RunStatus.class);
        for (AggregationExecutor.RunStatus status : AggregationExecutor.RunStatus.values()) {
            counters.put(status, Counter.builder("mine.telemetry.aggregation.runs")
                    .description("按固定结果统计的数值聚合调度轮次")
                    .tag("status", status.name())
                    .register(registry));
        }
        return Map.copyOf(counters);
    }

    /**
     * 注册整型原子值仪表。
     */
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

    /**
     * 注册长整型原子值仪表。
     */
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
