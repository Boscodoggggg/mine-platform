package com.bosco.mine.telemetry.aggregation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * 使用单线程固定延迟触发数值聚合，并隔离单轮异常避免调度永久停止。
 */
public final class AggregationScheduler implements ApplicationRunner, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AggregationScheduler.class);

    private final AggregationExecutor executor;
    private final AggregationMetrics metrics;
    private final AggregationRecomputeWorker recomputeWorker;
    private final TaskScheduler taskScheduler;
    private final Duration initialDelay;
    private final Duration interval;
    private final Clock clock;
    private ScheduledFuture<?> scheduled;
    private boolean closed;

    /**
     * 创建使用 UTC 系统时钟的数值聚合固定延迟协调器。
     */
    public AggregationScheduler(
            AggregationExecutor executor,
            AggregationMetrics metrics,
            TaskScheduler taskScheduler,
            Duration initialDelay,
            Duration interval
    ) {
        this(executor, metrics, null, taskScheduler, initialDelay, interval, Clock.systemUTC());
    }

    /**
     * 创建同时消费显式重算租约的数值聚合固定延迟协调器。
     */
    public AggregationScheduler(
            AggregationExecutor executor,
            AggregationMetrics metrics,
            AggregationRecomputeWorker recomputeWorker,
            TaskScheduler taskScheduler,
            Duration initialDelay,
            Duration interval
    ) {
        this(executor, metrics, recomputeWorker, taskScheduler,
                initialDelay, interval, Clock.systemUTC());
    }

    /**
     * 创建可注入确定性时钟的调度测试实例。
     */
    AggregationScheduler(
            AggregationExecutor executor,
            AggregationMetrics metrics,
            TaskScheduler taskScheduler,
            Duration initialDelay,
            Duration interval,
            Clock clock
    ) {
        this(executor, metrics, null, taskScheduler, initialDelay, interval, clock);
    }

    /**
     * 创建可注入显式重算工作器和确定性时钟的调度测试实例。
     */
    AggregationScheduler(
            AggregationExecutor executor,
            AggregationMetrics metrics,
            AggregationRecomputeWorker recomputeWorker,
            TaskScheduler taskScheduler,
            Duration initialDelay,
            Duration interval,
            Clock clock
    ) {
        this.executor = Objects.requireNonNull(executor, "数值聚合执行器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "数值聚合指标不能为空");
        this.recomputeWorker = recomputeWorker;
        this.taskScheduler = Objects.requireNonNull(
                taskScheduler, "数值聚合任务调度器不能为空");
        this.initialDelay = requireNonNegative(initialDelay, "数值聚合首次运行延迟");
        this.interval = requirePositive(interval, "数值聚合固定调度延迟");
        this.clock = Objects.requireNonNull(clock, "数值聚合调度时钟不能为空");
    }

    /** 应用启动完成后注册唯一的固定延迟任务。 */
    @Override
    public synchronized void run(ApplicationArguments args) {
        if (closed || scheduled != null) {
            return;
        }
        scheduled = Objects.requireNonNull(taskScheduler.scheduleWithFixedDelay(
                this::executeSafely,
                clock.instant().plus(initialDelay),
                interval
        ), "数值聚合任务调度器未返回取消句柄");
    }

    /** 取消尚未开始的后续轮次，正在提交的数据库批次不被强制中断。 */
    @Override
    public synchronized void close() {
        closed = true;
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    /**
     * 隔离单轮未收敛异常并记录固定低基数运行信息。
     */
    private void executeSafely() {
        metrics.executionStarted();
        try {
            AggregationExecutor.RunResult result = executor.runOnce();
            metrics.recordResult(result);
            LOGGER.info(
                    "数值聚合调度完成: executionId={}, status={}, policies={}, rules={}, sourceWatermark={}, buckets={}, lateIgnored={}",
                    result.executionId(), result.status(), result.policyCount(),
                    result.ruleCount(), result.sourceWatermark(), result.bucketCount(),
                    result.lateRowsIgnored());
        } catch (RuntimeException exception) {
            metrics.recordUnhandledFailure();
            LOGGER.error("数值聚合调度发生未收敛异常: exceptionType={}",
                    exception.getClass().getSimpleName());
        } finally {
            metrics.executionStopped();
        }
        executeRecomputeSafely();
    }

    /**
     * 与常规聚合失败相互隔离地消费至多一个显式重算租约。
     */
    private void executeRecomputeSafely() {
        if (recomputeWorker == null) return;
        try {
            AggregationRecomputeWorker.WorkResult result = recomputeWorker.runOnce();
            LOGGER.info(
                    "显式聚合重算调度完成: requestId={}, executionId={}, status={}, buckets={}",
                    result.requestId(), result.executionId(), result.status(),
                    result.bucketCount());
        } catch (RuntimeException exception) {
            LOGGER.error("显式聚合重算调度发生未收敛异常: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * 校验固定延迟必须为正数。
     */
    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于零");
        }
        return value;
    }

    /**
     * 校验首次延迟不得为负数。
     */
    private Duration requireNonNegative(Duration value, String label) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(label + "不能小于零");
        }
        return value;
    }
}
