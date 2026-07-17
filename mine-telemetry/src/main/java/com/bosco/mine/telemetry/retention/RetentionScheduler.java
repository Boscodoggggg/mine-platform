package com.bosco.mine.telemetry.retention;

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
 * 使用单线程固定延迟触发数据保留任务，并隔离单轮异常避免调度永久停止。
 */
public final class RetentionScheduler implements ApplicationRunner, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionExecutor executor;
    private final RetentionMetrics metrics;
    private final TaskScheduler taskScheduler;
    private final Duration initialDelay;
    private final Duration interval;
    private final Clock clock;
    private ScheduledFuture<?> scheduled;
    private boolean closed;

    /**
     * 创建使用 UTC 系统时钟的数据保留固定延迟协调器。
     *
     * @param executor 数据保留执行器
     * @param metrics 数据保留低基数指标
     * @param taskScheduler 独立单线程任务调度器
     * @param initialDelay 首次运行延迟
     * @param interval 每轮完成后的固定延迟
     */
    public RetentionScheduler(
            RetentionExecutor executor,
            RetentionMetrics metrics,
            TaskScheduler taskScheduler,
            Duration initialDelay,
            Duration interval
    ) {
        this(executor, metrics, taskScheduler, initialDelay, interval, Clock.systemUTC());
    }

    /** 创建可注入确定性时钟的调度测试构造器。 */
    RetentionScheduler(
            RetentionExecutor executor,
            RetentionMetrics metrics,
            TaskScheduler taskScheduler,
            Duration initialDelay,
            Duration interval,
            Clock clock
    ) {
        this.executor = Objects.requireNonNull(executor, "数据保留执行器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "数据保留指标不能为空");
        this.taskScheduler = Objects.requireNonNull(
                taskScheduler, "数据保留任务调度器不能为空");
        this.initialDelay = requireNonNegative(initialDelay, "数据保留首次运行延迟");
        this.interval = requirePositive(interval, "数据保留固定调度延迟");
        this.clock = Objects.requireNonNull(clock, "数据保留调度时钟不能为空");
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
        ), "数据保留任务调度器未返回取消句柄");
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

    private void executeSafely() {
        metrics.executionStarted();
        try {
            RetentionExecutor.RunResult result = executor.runOnce();
            metrics.recordResult(result);
            LOGGER.info(
                    "数据保留调度完成: executionId={}, status={}, policies={}, aggregationBlocked={}, rawDeleted={}, qualityDeleted={}, batches={}",
                    result.executionId(), result.status(), result.policyCount(),
                    result.aggregationBlockedRows(),
                    result.rawRowsDeleted(), result.qualityRowsDeleted(), result.batchCount());
        } catch (RuntimeException exception) {
            metrics.recordUnhandledFailure();
            LOGGER.error("数据保留调度发生未收敛异常: exceptionType={}",
                    exception.getClass().getSimpleName());
        } finally {
            metrics.executionStopped();
        }
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于零");
        }
        return value;
    }

    private Duration requireNonNegative(Duration value, String label) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(label + "不能小于零");
        }
        return value;
    }
}
