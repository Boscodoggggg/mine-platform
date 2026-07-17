package com.bosco.mine.telemetry.alarm.outbox;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在应用启动和 RabbitMQ 真正重连后执行告警 Outbox 有界恢复。
 */
public final class TelemetryOutboxRecovery
        implements ApplicationRunner, SmartInitializingSingleton {

    private final TelemetryOutboxRelay relay;
    private final CachingConnectionFactory connectionFactory;
    private final Executor executor;
    private final AtomicBoolean connectionSeen = new AtomicBoolean();

    /**
     * 创建告警 Outbox 恢复协调器。
     *
     * @param relay 告警 Outbox 中继器
     * @param connectionFactory RabbitMQ 连接工厂
     * @param executor 有界恢复执行器
     */
    public TelemetryOutboxRecovery(
            TelemetryOutboxRelay relay,
            CachingConnectionFactory connectionFactory,
            Executor executor
    ) {
        this.relay = Objects.requireNonNull(relay, "告警 Outbox 中继器不能为空");
        this.connectionFactory = Objects.requireNonNull(
                connectionFactory, "告警 RabbitMQ 连接工厂不能为空");
        this.executor = Objects.requireNonNull(executor, "告警恢复执行器不能为空");
    }

    /** 首次建连由启动恢复覆盖，后续建连才视为需要恢复的重连。 */
    @Override
    public void afterSingletonsInstantiated() {
        connectionFactory.addConnectionListener(connection -> {
            if (connectionSeen.compareAndSet(false, true)) {
                return;
            }
            executor.execute(relay::recoverBounded);
        });
    }

    /** 应用就绪阶段异步执行一次有界恢复。 */
    @Override
    public void run(ApplicationArguments args) {
        executor.execute(relay::recoverBounded);
    }
}
