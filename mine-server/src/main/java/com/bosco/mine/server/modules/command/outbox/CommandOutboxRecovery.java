package com.bosco.mine.server.modules.command.outbox;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在应用启动和 RabbitMQ 真正重连后各执行一次有界恢复。
 */
public final class CommandOutboxRecovery implements ApplicationRunner, SmartInitializingSingleton {
    private final CommandOutboxRelay relay;
    private final CachingConnectionFactory connectionFactory;
    private final Executor executor;
    private final AtomicBoolean connectionSeen = new AtomicBoolean();

    /**
     * 创建恢复协调器。
     *
     * @param relay Outbox 中继器
     * @param connectionFactory RabbitMQ 连接工厂
     * @param executor 恢复执行器
     */
    public CommandOutboxRecovery(
            CommandOutboxRelay relay,
            CachingConnectionFactory connectionFactory,
            Executor executor
    ) {
        this.relay = Objects.requireNonNull(relay, "Outbox 中继器不能为空");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "RabbitMQ 连接工厂不能为空");
        this.executor = Objects.requireNonNull(executor, "恢复执行器不能为空");
    }

    /**
     * 注册连接监听；首次建连由启动恢复覆盖，后续建连才视为重连。
     */
    @Override
    public void afterSingletonsInstantiated() {
        connectionFactory.addConnectionListener(connection -> {
            if (connectionSeen.compareAndSet(false, true)) {
                return;
            }
            executor.execute(relay::recoverBounded);
        });
    }

    /**
     * 应用就绪阶段执行一次有界恢复。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        executor.execute(relay::recoverBounded);
    }
}
