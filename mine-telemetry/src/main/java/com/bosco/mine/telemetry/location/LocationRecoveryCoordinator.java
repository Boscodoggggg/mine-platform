package com.bosco.mine.telemetry.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在启动、RabbitMQ 重连和固定周期执行位置 Outbox 与 Redis 当前位置有界恢复。
 */
public final class LocationRecoveryCoordinator
        implements ApplicationRunner, SmartInitializingSingleton {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LocationRecoveryCoordinator.class);

    private final LocationRealtimeOutboxRelay relay;
    private final LocationLatestCache cache;
    private final CachingConnectionFactory connectionFactory;
    private final Executor executor;
    private final TaskScheduler scheduler;
    private final Duration outboxInterval;
    private final Duration cacheInterval;
    private final int siteLimit;
    private final int tagLimit;
    private final AtomicBoolean connectionSeen = new AtomicBoolean();
    private final AtomicBoolean outboxQueued = new AtomicBoolean();
    private final AtomicBoolean cacheQueued = new AtomicBoolean();

    /** 创建不会并发重复执行同类恢复任务的协调器。 */
    public LocationRecoveryCoordinator(
            LocationRealtimeOutboxRelay relay,
            LocationLatestCache cache,
            CachingConnectionFactory connectionFactory,
            Executor executor,
            TaskScheduler scheduler,
            Duration outboxInterval,
            Duration cacheInterval,
            int siteLimit,
            int tagLimit
    ) {
        this.relay = Objects.requireNonNull(relay, "位置实时 Outbox 中继器不能为空");
        this.cache = Objects.requireNonNull(cache, "位置 Redis 缓存不能为空");
        this.connectionFactory = Objects.requireNonNull(
                connectionFactory, "位置 RabbitMQ 连接工厂不能为空");
        this.executor = Objects.requireNonNull(executor, "位置恢复执行器不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "位置恢复调度器不能为空");
        this.outboxInterval = requirePositive(outboxInterval, "位置 Outbox 恢复间隔");
        this.cacheInterval = requirePositive(cacheInterval, "位置缓存恢复间隔");
        if (siteLimit < 1 || tagLimit < 1) {
            throw new IllegalArgumentException("位置缓存恢复上限必须大于 0");
        }
        this.siteLimit = siteLimit;
        this.tagLimit = tagLimit;
    }

    /** 首次建连由启动恢复覆盖，后续重连精确触发 Outbox 恢复。 */
    @Override
    public void afterSingletonsInstantiated() {
        connectionFactory.addConnectionListener(connection -> {
            if (connectionSeen.compareAndSet(false, true)) {
                return;
            }
            submitOutboxRecovery();
        });
    }

    /** 应用就绪时恢复一次，并注册固定间隔的持久恢复任务。 */
    @Override
    public void run(ApplicationArguments args) {
        submitOutboxRecovery();
        submitCacheRecovery();
        scheduler.scheduleWithFixedDelay(this::submitOutboxRecovery, outboxInterval);
        scheduler.scheduleWithFixedDelay(this::submitCacheRecovery, cacheInterval);
    }

    private void submitOutboxRecovery() {
        submit(outboxQueued, () -> relay.recoverBounded(), "位置 Outbox");
    }

    private void submitCacheRecovery() {
        submit(cacheQueued, () -> cache.rebuildBounded(siteLimit, tagLimit), "位置缓存");
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
                    LOGGER.warn("{}有界恢复失败，将等待下一周期", label, exception);
                } finally {
                    queued.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            queued.set(false);
            LOGGER.warn("{}恢复执行器已饱和，本轮跳过", label);
        }
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }
}
