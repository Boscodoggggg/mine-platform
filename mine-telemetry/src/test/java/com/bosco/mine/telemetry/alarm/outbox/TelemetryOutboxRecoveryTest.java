package com.bosco.mine.telemetry.alarm.outbox;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 校验周期恢复只排队一个任务，避免 Broker 故障时恢复扫描无限堆积。
 */
class TelemetryOutboxRecoveryTest {

    @Test
    void 周期扫描应合并并发恢复请求且完成后允许下一轮() throws Exception {
        TelemetryOutboxRelay relay = mock(TelemetryOutboxRelay.class);
        CachingConnectionFactory connectionFactory = mock(CachingConnectionFactory.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> queued = new ArrayList<>();
        Executor executor = queued::add;
        TelemetryOutboxRecovery recovery = new TelemetryOutboxRecovery(
                relay, connectionFactory, executor, scheduler, Duration.ofSeconds(10));

        recovery.run(null);
        ArgumentCaptor<Runnable> periodic = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(
                periodic.capture(), eq(Duration.ofSeconds(10)));
        periodic.getValue().run();

        assertThat(queued).hasSize(1);
        queued.removeFirst().run();
        verify(relay).recoverBounded();

        periodic.getValue().run();
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();
        verify(relay, times(2)).recoverBounded();
    }
}
