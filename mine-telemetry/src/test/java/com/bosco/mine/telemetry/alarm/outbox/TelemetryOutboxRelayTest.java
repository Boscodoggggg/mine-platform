package com.bosco.mine.telemetry.alarm.outbox;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验告警 Outbox 的精确发布、退避、死信和有界恢复。
 */
class TelemetryOutboxRelayTest {

    private static final UUID EVENT_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000011");
    private static final UUID OCCURRENCE_ID =
            UUID.fromString("01980a3b-1200-7000-8000-000000000012");
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");

    @Test
    void 应只认领指定事件并在Broker确认后标记发布成功() {
        TelemetryOutboxRepository repository = mock(TelemetryOutboxRepository.class);
        TelemetryOutboxRelay.Publisher publisher = mock(TelemetryOutboxRelay.Publisher.class);
        TelemetryOutboxRelay.RetryScheduler scheduler =
                mock(TelemetryOutboxRelay.RetryScheduler.class);
        TelemetryOutboxRepository.ClaimedEvent event = event(1);
        when(repository.claimById(EVENT_ID, "telemetry-1", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(event));

        relay(repository, publisher, scheduler).publishById(EVENT_ID);

        verify(publisher).publish(event);
        verify(repository).markPublished(EVENT_ID, "telemetry-1");
        verify(repository, never()).findRecoverableEventIds(100);
        verify(scheduler, never()).schedule(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 发布失败应指数退避且脱敏错误后只调度原事件() {
        TelemetryOutboxRepository repository = mock(TelemetryOutboxRepository.class);
        TelemetryOutboxRelay.Publisher publisher = mock(TelemetryOutboxRelay.Publisher.class);
        TelemetryOutboxRelay.RetryScheduler scheduler =
                mock(TelemetryOutboxRelay.RetryScheduler.class);
        TelemetryOutboxRepository.ClaimedEvent event = event(3);
        when(repository.claimById(EVENT_ID, "telemetry-1", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(event));
        org.mockito.Mockito.doThrow(new IllegalStateException(
                "broker password=do-not-store token:abc unavailable"))
                .when(publisher).publish(event);

        relay(repository, publisher, scheduler).publishById(EVENT_ID);

        Instant retryAt = NOW.plusSeconds(4);
        verify(repository).markFailed(
                EVENT_ID,
                "telemetry-1",
                retryAt,
                "broker password=[REDACTED] token=[REDACTED] unavailable"
        );
        verify(scheduler).schedule(EVENT_ID, retryAt);
    }

    @Test
    void 达到尝试上限应进入死信且不再调度() {
        TelemetryOutboxRepository repository = mock(TelemetryOutboxRepository.class);
        TelemetryOutboxRelay.Publisher publisher = mock(TelemetryOutboxRelay.Publisher.class);
        TelemetryOutboxRelay.RetryScheduler scheduler =
                mock(TelemetryOutboxRelay.RetryScheduler.class);
        TelemetryOutboxRepository.ClaimedEvent event = event(5);
        when(repository.claimById(EVENT_ID, "telemetry-1", Duration.ofSeconds(30)))
                .thenReturn(Optional.of(event));
        org.mockito.Mockito.doThrow(new IllegalStateException("confirm timeout"))
                .when(publisher).publish(event);

        relay(repository, publisher, scheduler).publishById(EVENT_ID);

        verify(repository).markDead(EVENT_ID, "telemetry-1", "confirm timeout");
        verify(scheduler, never()).schedule(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 启动恢复应受上限约束并逐个精确认领() {
        TelemetryOutboxRepository repository = mock(TelemetryOutboxRepository.class);
        TelemetryOutboxRelay.Publisher publisher = mock(TelemetryOutboxRelay.Publisher.class);
        TelemetryOutboxRelay.RetryScheduler scheduler =
                mock(TelemetryOutboxRelay.RetryScheduler.class);
        UUID second = UUID.fromString("01980a3b-1200-7000-8000-000000000013");
        when(repository.findRecoverableEventIds(100)).thenReturn(List.of(EVENT_ID, second));

        int recovered = relay(repository, publisher, scheduler).recoverBounded();

        assertThat(recovered).isEqualTo(2);
        verify(repository).claimById(EVENT_ID, "telemetry-1", Duration.ofSeconds(30));
        verify(repository).claimById(second, "telemetry-1", Duration.ofSeconds(30));
    }

    private TelemetryOutboxRelay relay(
            TelemetryOutboxRepository repository,
            TelemetryOutboxRelay.Publisher publisher,
            TelemetryOutboxRelay.RetryScheduler scheduler
    ) {
        return new TelemetryOutboxRelay(
                repository,
                publisher,
                scheduler,
                "telemetry-1",
                Duration.ofSeconds(30),
                5,
                100,
                Duration.ofSeconds(1),
                Duration.ofMinutes(1),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private TelemetryOutboxRepository.ClaimedEvent event(int attempts) {
        return new TelemetryOutboxRepository.ClaimedEvent(
                EVENT_ID,
                OCCURRENCE_ID,
                "alarm.raised",
                1,
                "{\"alarmOccurrenceId\":\"" + OCCURRENCE_ID + "\"}",
                "{\"traceId\":\"trace-1\",\"partitionKey\":\"device-1\"}",
                attempts,
                NOW.minusSeconds(60)
        );
    }
}
