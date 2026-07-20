package com.bosco.mine.server.modules.mine.operation.outbox;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 校验运营 Outbox 周期恢复合并并发请求且不会形成无界任务积压。
 */
class OperationOutboxRecoveryTest {

    @Test
    void 启动和周期恢复必须合并并发请求且完成后允许下一轮() throws Exception {
        OperationOutboxRelay relay = mock(OperationOutboxRelay.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> queued = new ArrayList<>();
        Executor executor = queued::add;
        OperationOutboxRecovery recovery = new OperationOutboxRecovery(
                relay, executor, scheduler, Duration.ofSeconds(1));

        recovery.run(null);
        ArgumentCaptor<Runnable> periodic = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(
                periodic.capture(), eq(Duration.ofSeconds(1)));
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
