package com.bosco.mine.telemetry.spatial;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 校验空间评估与 Outbox 恢复在启动、周期和 RabbitMQ 重连时自动运行。
 */
class SpatialEventRuntimeCoordinatorTest {

    @Test
    void 应在启动后执行并注册周期任务且重连触发Outbox恢复() {
        SpatialEvaluationWorker worker = mock(SpatialEvaluationWorker.class);
        SpatialEventOutboxRelay relay = mock(SpatialEventOutboxRelay.class);
        CachingConnectionFactory connectionFactory = mock(CachingConnectionFactory.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        Executor directExecutor = Runnable::run;
        SpatialEventRuntimeCoordinator coordinator = new SpatialEventRuntimeCoordinator(
                worker, relay, connectionFactory, directExecutor, scheduler,
                Duration.ofMillis(250), 25, Duration.ofSeconds(10));

        coordinator.afterSingletonsInstantiated();
        coordinator.run(null);

        verify(worker).processReady(any(Instant.class), eq(25));
        verify(relay).recoverBounded();
        verify(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), eq(Duration.ofMillis(250)));
        verify(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), eq(Duration.ofSeconds(10)));

        ArgumentCaptor<ConnectionListener> listener =
                ArgumentCaptor.forClass(ConnectionListener.class);
        verify(connectionFactory).addConnectionListener(listener.capture());
        listener.getValue().onCreate(mock(Connection.class));
        listener.getValue().onCreate(mock(Connection.class));

        verify(relay, times(2)).recoverBounded();
    }
}
