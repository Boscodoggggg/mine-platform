package com.bosco.mine.server.modules.mine.operation.outbox;

import com.bosco.mine.messaging.realtime.RealtimeOperationEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验运营 Outbox 的随机租约、确认发布、退避、死信和有界恢复。
 */
class OperationOutboxRelayTest {

    private static final UUID EVENT_ID = uuid(1);
    private static final UUID ORGANIZATION_ID = uuid(2);
    private static final UUID SITE_ID = uuid(3);
    private static final UUID AGGREGATE_ID = uuid(4);
    private static final Instant NOW = Instant.parse("2026-07-20T05:00:00Z");

    @Test
    void 确认发布后必须使用同一随机租约标记成功() {
        OperationOutboxRelay.Repository repository =
                mock(OperationOutboxRelay.Repository.class);
        OperationOutboxRelay.Publisher publisher =
                mock(OperationOutboxRelay.Publisher.class);
        var token = org.mockito.ArgumentCaptor.forClass(UUID.class);
        when(repository.claimById(eq(EVENT_ID), token.capture(), eq(Duration.ofSeconds(30))))
                .thenReturn(Optional.of(event(1)));
        OperationOutboxRelay relay = relay(repository, publisher);

        relay.publishById(EVENT_ID);

        verify(publisher).publish(event(1));
        verify(repository).markPublished(EVENT_ID, token.getValue());
        assertThat(token.getValue().version()).isEqualTo(4);
    }

    @Test
    void 发布失败必须指数退避且达到上限进入死信() {
        OperationOutboxRelay.Repository repository =
                mock(OperationOutboxRelay.Repository.class);
        OperationOutboxRelay.Publisher publisher =
                mock(OperationOutboxRelay.Publisher.class);
        when(repository.claimById(eq(EVENT_ID), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(Optional.of(event(2)), Optional.of(event(5)));
        org.mockito.Mockito.doThrow(new IllegalStateException("confirm timeout"))
                .when(publisher).publish(any());
        OperationOutboxRelay relay = relay(repository, publisher);

        relay.publishById(EVENT_ID);
        relay.publishById(EVENT_ID);

        verify(repository).markFailed(
                eq(EVENT_ID), any(UUID.class), eq(NOW.plusSeconds(2)),
                eq("confirm timeout"));
        verify(repository).markDead(eq(EVENT_ID), any(UUID.class), eq("confirm timeout"));
        verify(repository, never()).markPublished(eq(EVENT_ID), any());
    }

    @Test
    void 启动恢复必须有界且每次领取使用不同随机租约() {
        OperationOutboxRelay.Repository repository =
                mock(OperationOutboxRelay.Repository.class);
        OperationOutboxRelay.Publisher publisher =
                mock(OperationOutboxRelay.Publisher.class);
        UUID second = uuid(5);
        when(repository.findRecoverableEventIds(100)).thenReturn(List.of(EVENT_ID, second));
        when(repository.claimById(eq(EVENT_ID), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(Optional.empty());
        when(repository.claimById(eq(second), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(Optional.empty());
        OperationOutboxRelay relay = relay(repository, publisher);

        assertThat(relay.recoverBounded()).isEqualTo(2);

        var tokens = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(repository).claimById(eq(EVENT_ID), tokens.capture(), eq(Duration.ofSeconds(30)));
        verify(repository).claimById(eq(second), tokens.capture(), eq(Duration.ofSeconds(30)));
        assertThat(tokens.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void Rabbit发布器必须发送持久有界消息并等待确认() throws Exception {
        RabbitOperations operations = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(operations.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        RabbitOperationEventPublisher publisher =
                new RabbitOperationEventPublisher(operations, 3000L);

        publisher.publish(event(1));

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(
                eq("mine.realtime.x"), eq("realtime.operation." + SITE_ID),
                message.capture());
        verify(scoped).waitForConfirmsOrDie(3000L);
        Message sent = message.getValue();
        assertThat(sent.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(sent.getMessageProperties().getHeaders())
                .containsEntry("eventType", "realtime.operation.updated")
                .containsEntry("schemaVersion", 1)
                .containsEntry("siteId", SITE_ID.toString())
                .containsEntry("updateType", "RECORD_COMPLETED")
                .containsEntry("businessSequence", 17L);
        Map<String, Object> envelope = JsonMapper.builder().findAndAddModules().build()
                .readValue(sent.getBody(), new TypeReference<>() { });
        assertThat(envelope.toString())
                .contains(EVENT_ID.toString(), ORGANIZATION_ID.toString(), SITE_ID.toString())
                .contains("RECORD_COMPLETED", "businessSequence=17")
                .doesNotContain("password", "certificate", "phone");
        assertThat(new String(sent.getBody(), StandardCharsets.UTF_8)).hasSizeLessThan(8192);
    }

    @Test
    void 运营事件必须拒绝非UUIDv7和超大摘要() {
        assertThatThrownBy(() -> new RealtimeOperationEvent(
                UUID.randomUUID(), ORGANIZATION_ID, SITE_ID,
                RealtimeOperationEvent.AggregateType.OPERATION_RECORD,
                AGGREGATE_ID, "RECORD_COMPLETED", 17L, NOW,
                Map.of("recordId", AGGREGATE_ID.toString())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeOperationEvent(
                EVENT_ID, ORGANIZATION_ID, SITE_ID,
                RealtimeOperationEvent.AggregateType.OPERATION_RECORD,
                AGGREGATE_ID, "RECORD_COMPLETED", 17L, NOW,
                Map.of("oversized", "x".repeat(5000))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeOperationEvent(
                EVENT_ID, ORGANIZATION_ID, SITE_ID,
                RealtimeOperationEvent.AggregateType.OPERATION_RECORD,
                AGGREGATE_ID, "RECORD_CUSTOM_42", 17L, NOW,
                Map.of("recordId", AGGREGATE_ID.toString())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeOperationEvent(
                EVENT_ID, ORGANIZATION_ID, SITE_ID,
                RealtimeOperationEvent.AggregateType.OPERATION_RECORD,
                AGGREGATE_ID, "RECORD_COMPLETED", 17L, NOW,
                Map.of("accessTokenHash", "sensitive")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeOperationEvent(
                EVENT_ID, ORGANIZATION_ID, SITE_ID,
                RealtimeOperationEvent.AggregateType.OPERATION_RECORD,
                AGGREGATE_ID, "RECORD_COMPLETED", 17L, NOW,
                Map.of("description", "矿".repeat(900),
                        "detail", "山".repeat(900))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private OperationOutboxRelay relay(
            OperationOutboxRelay.Repository repository,
            OperationOutboxRelay.Publisher publisher
    ) {
        return new OperationOutboxRelay(
                repository, publisher,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30),
                5, 100, Duration.ofSeconds(1), Duration.ofMinutes(1));
    }

    private OperationOutboxRelay.ClaimedEvent event(int attemptCount) {
        return new OperationOutboxRelay.ClaimedEvent(
                EVENT_ID, ORGANIZATION_ID, SITE_ID,
                "OPERATION_RECORD", AGGREGATE_ID,
                "realtime.operation.updated", 1, 17L,
                "{\"siteId\":\"" + SITE_ID + "\","
                        + "\"updateType\":\"RECORD_COMPLETED\","
                        + "\"operationRecordId\":\"" + AGGREGATE_ID + "\"}",
                attemptCount, NOW.minusSeconds(1));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7d00-0000-7000-8000-%012d".formatted(suffix));
    }
}
