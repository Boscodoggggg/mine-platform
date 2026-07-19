package com.bosco.mine.telemetry.spatial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在应用启动、固定周期和 RabbitMQ 重连时驱动空间评估与空间 Outbox 恢复。
 */
public final class SpatialEventRuntimeCoordinator
        implements ApplicationRunner, SmartInitializingSingleton {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            SpatialEventRuntimeCoordinator.class);

    private final SpatialEvaluationWorker worker;
    private final SpatialEventOutboxRelay relay;
    private final CachingConnectionFactory connectionFactory;
    private final Executor executor;
    private final TaskScheduler scheduler;
    private final Duration evaluationInterval;
    private final int evaluationBatchSize;
    private final Duration outboxRecoveryInterval;
    private final AtomicBoolean connectionSeen = new AtomicBoolean();
    private final AtomicBoolean evaluationQueued = new AtomicBoolean();
    private final AtomicBoolean outboxQueued = new AtomicBoolean();

    /**
     * 创建避免同类任务并发重入的空间运行协调器。
     *
     * @param worker 空间事件时间评估器
     * @param relay 空间事件 Outbox 中继器
     * @param connectionFactory RabbitMQ 连接工厂
     * @param executor 空间运行有界执行器
     * @param scheduler 空间运行调度器
     * @param evaluationInterval 空间评估周期
     * @param evaluationBatchSize 单轮空间评估上限
     * @param outboxRecoveryInterval 空间 Outbox 恢复周期
     */
    public SpatialEventRuntimeCoordinator(
            SpatialEvaluationWorker worker,
            SpatialEventOutboxRelay relay,
            CachingConnectionFactory connectionFactory,
            Executor executor,
            TaskScheduler scheduler,
            Duration evaluationInterval,
            int evaluationBatchSize,
            Duration outboxRecoveryInterval
    ) {
        this.worker = Objects.requireNonNull(worker, "空间评估器不能为空");
        this.relay = Objects.requireNonNull(relay, "空间 Outbox 中继器不能为空");
        this.connectionFactory = Objects.requireNonNull(
                connectionFactory, "空间 RabbitMQ 连接工厂不能为空");
        this.executor = Objects.requireNonNull(executor, "空间运行执行器不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "空间运行调度器不能为空");
        this.evaluationInterval = requirePositive(evaluationInterval, "空间评估周期");
        this.outboxRecoveryInterval = requirePositive(
                outboxRecoveryInterval, "空间 Outbox 恢复周期");
        if (evaluationBatchSize < 1 || evaluationBatchSize > 10_000) {
            throw new IllegalArgumentException("空间评估单轮上限必须在 1 到 10000 之间");
        }
        this.evaluationBatchSize = evaluationBatchSize;
    }

    /** 首次建连由启动恢复覆盖，后续重连精确触发空间 Outbox 恢复。 */
    @Override
    public void afterSingletonsInstantiated() {
        connectionFactory.addConnectionListener(connection -> {
            if (connectionSeen.compareAndSet(false, true)) {
                return;
            }
            submitOutboxRecovery();
        });
    }

    /** 应用就绪时立即运行一次，并注册两个固定间隔的可靠任务。 */
    @Override
    public void run(ApplicationArguments args) {
        submitEvaluation();
        submitOutboxRecovery();
        scheduler.scheduleWithFixedDelay(this::submitEvaluation, evaluationInterval);
        scheduler.scheduleWithFixedDelay(this::submitOutboxRecovery, outboxRecoveryInterval);
    }

    private void submitEvaluation() {
        submit(evaluationQueued, () -> {
            if (worker.processReady(Instant.now(), evaluationBatchSize) > 0) {
                submitOutboxRecovery();
            }
        }, "空间评估");
    }

    private void submitOutboxRecovery() {
        submit(outboxQueued, relay::recoverBounded, "空间 Outbox");
    }

    private void submit(AtomicBoolean queued, Runnable work, String label) {
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException exception) {
                    LOGGER.warn("{}任务失败，将等待下一周期恢复", label, exception);
                } finally {
                    queued.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            queued.set(false);
            LOGGER.warn("{}执行器已饱和，本轮跳过", label);
        }
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }
}
