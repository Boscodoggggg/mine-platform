package com.bosco.mine.server.modules.command.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验 Outbox 只按事件标识发布、失败重试和有界恢复。
 */
class CommandOutboxRelayTest {

    private static final UUID EVENT_ID = UUID.fromString("019f64c4-a000-7000-8000-000000000001");

    @Test
    void 提交唤醒应只认领指定事件并在确认后标记发布成功() {
        OutboxRelayRepository repository = mock(OutboxRelayRepository.class);
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        KnownEventRetryScheduler scheduler = mock(KnownEventRetryScheduler.class);
        OutboxEvent event = event(1);
        when(repository.claimById(EVENT_ID, "server-1", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(event));
        CommandOutboxRelay relay = relay(repository, publisher, scheduler);

        relay.publishById(EVENT_ID);

        verify(publisher).publish(event);
        verify(repository).markPublished(EVENT_ID, "server-1");
        verify(repository, never()).findRecoverableEventIds(100);
        verify(scheduler, never()).schedule(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 发布失败应只登记并调度原事件标识() {
        OutboxRelayRepository repository = mock(OutboxRelayRepository.class);
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        KnownEventRetryScheduler scheduler = mock(KnownEventRetryScheduler.class);
        OutboxEvent event = event(2);
        when(repository.claimById(EVENT_ID, "server-1", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(event));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish(event);
        CommandOutboxRelay relay = relay(repository, publisher, scheduler);

        relay.publishById(EVENT_ID);

        verify(repository).markFailed(
                org.mockito.ArgumentMatchers.eq(EVENT_ID),
                org.mockito.ArgumentMatchers.eq("server-1"),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.contains("broker unavailable")
        );
        verify(scheduler).schedule(
                org.mockito.ArgumentMatchers.eq(EVENT_ID),
                org.mockito.ArgumentMatchers.any(Instant.class)
        );
    }

    @Test
    void 达到尝试上限后应进入死信状态且不再进程内重试() {
        OutboxRelayRepository repository = mock(OutboxRelayRepository.class);
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        KnownEventRetryScheduler scheduler = mock(KnownEventRetryScheduler.class);
        OutboxEvent event = event(5);
        when(repository.claimById(EVENT_ID, "server-1", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(event));
        org.mockito.Mockito.doThrow(new IllegalStateException("confirm timeout"))
                .when(publisher).publish(event);
        CommandOutboxRelay relay = relay(repository, publisher, scheduler);

        relay.publishById(EVENT_ID);

        verify(repository).markDead(EVENT_ID, "server-1", "confirm timeout");
        verify(scheduler, never()).schedule(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 启动恢复应只读取配置上限并逐个精确发布() {
        OutboxRelayRepository repository = mock(OutboxRelayRepository.class);
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        KnownEventRetryScheduler scheduler = mock(KnownEventRetryScheduler.class);
        UUID second = UUID.fromString("019f64c4-a000-7000-8000-000000000002");
        when(repository.findRecoverableEventIds(100)).thenReturn(List.of(EVENT_ID, second));
        when(repository.claimById(EVENT_ID, "server-1", Duration.ofSeconds(30)))
                .thenReturn(Optional.empty());
        when(repository.claimById(second, "server-1", Duration.ofSeconds(30)))
                .thenReturn(Optional.empty());
        CommandOutboxRelay relay = relay(repository, publisher, scheduler);

        int recovered = relay.recoverBounded();

        assertThat(recovered).isEqualTo(2);
        verify(repository).findRecoverableEventIds(100);
        verify(repository).claimById(EVENT_ID, "server-1", Duration.ofSeconds(30));
        verify(repository).claimById(second, "server-1", Duration.ofSeconds(30));
    }

    private CommandOutboxRelay relay(
            OutboxRelayRepository repository,
            OutboxEventPublisher publisher,
            KnownEventRetryScheduler scheduler
    ) {
        return new CommandOutboxRelay(
                repository, publisher, scheduler, "server-1",
                Duration.ofSeconds(30), 5, 100,
                Duration.ofSeconds(1), Duration.ofMinutes(1)
        );
    }

    private OutboxEvent event(int attempts) {
        return new OutboxEvent(
                EVENT_ID, UUID.fromString("019f64c4-a000-7000-8000-000000000010"),
                "command.dispatch.requested", 1,
                "{\"deviceId\":\"019f64c4-a000-7000-8000-000000000020\"}",
                "{\"traceId\":\"trace-1\"}", attempts,
                Instant.parse("2026-07-15T07:00:00Z")
        );
    }
}
