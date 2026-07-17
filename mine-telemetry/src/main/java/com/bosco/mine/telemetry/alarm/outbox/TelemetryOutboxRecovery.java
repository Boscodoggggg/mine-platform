package com.bosco.mine.telemetry.alarm.outbox;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在应用启动和 RabbitMQ 真正重连后执行告警 Outbox 有界恢复。
 */
public final class TelemetryOutboxRecovery
        implements ApplicationRunner, SmartInitializingSingleton {

    private static final Logger LOGGER = LoggerFactory.getLogger(TelemetryOutboxRecovery.class);

    private final TelemetryOutboxRelay relay;
    private final CachingConnectionFactory connectionFactory;
    private final Executor executor;
    private final TaskScheduler scheduler;
    private final Duration recoveryInterval;
    private final AtomicBoolean connectionSeen = new AtomicBoolean();
    private final AtomicBoolean recoveryQueued = new AtomicBoolean();

    /**
     * 创建告警 Outbox 恢复协调器。
     *
     * @param relay 告警 Outbox 中继器
     * @param connectionFactory RabbitMQ 连接工厂
     * @param executor 有界恢复执行器
     * @param scheduler 周期恢复调度器
     * @param recoveryInterval 周期恢复间隔
     */
    public TelemetryOutboxRecovery(
            TelemetryOutboxRelay relay,
            CachingConnectionFactory connectionFactory,
            Executor executor,
            TaskScheduler scheduler,
            Duration recoveryInterval
    ) {
        this.relay = Objects.requireNonNull(relay, "告警 Outbox 中继器不能为空");
        this.connectionFactory = Objects.requireNonNull(
                connectionFactory, "告警 RabbitMQ 连接工厂不能为空");
        this.executor = Objects.requireNonNull(executor, "告警恢复执行器不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "告警恢复调度器不能为空");
        this.recoveryInterval = requirePositive(
                recoveryInterval, "告警 Outbox 周期恢复间隔");
    }

    /** 首次建连由启动恢复覆盖，后续建连才视为需要恢复的重连。 */
    @Override
    public void afterSingletonsInstantiated() {
        connectionFactory.addConnectionListener(connection -> {
            if (connectionSeen.compareAndSet(false, true)) {
                return;
            }
            submitRecovery();
        });
    }

    /** 应用就绪阶段启动一次恢复，并周期扫描持久待发布事件。 */
    @Override
    public void run(ApplicationArguments args) {
        submitRecovery();
        scheduler.scheduleWithFixedDelay(this::submitRecovery, recoveryInterval);
    }

    private void submitRecovery() {
        if (!recoveryQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    relay.recoverBounded();
                } finally {
                    recoveryQueued.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            recoveryQueued.set(false);
            LOGGER.warn("告警 Outbox 恢复执行器已饱和，本轮跳过并等待下次周期扫描");
        }
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }
}
