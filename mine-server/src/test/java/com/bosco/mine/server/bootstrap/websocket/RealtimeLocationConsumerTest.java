package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 校验位置实时消费者只接受生产者发布的版本化消息信封。
 */
class RealtimeLocationConsumerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 合法位置消息应先分发再确认() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeLocationConsumer consumer = new RealtimeLocationConsumer(registry);
        RealtimeLocationEvent event = event();
        Message message = message(envelope(event), 61L);

        consumer.onMessage(message, channel);

        var order = inOrder(registry, channel);
        order.verify(registry).publishLocation(event);
        order.verify(channel).basicAck(61L, false);
    }

    @Test
    void 信封和载荷矿区不一致时应拒绝且不重新入队() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeLocationConsumer consumer = new RealtimeLocationConsumer(registry);
        RealtimeLocationEvent event = event();
        MessageEnvelope<RealtimeLocationEvent> invalid = new MessageEnvelope<>(
                event.eventId(), MessageType.of("realtime.location.updated"), 1,
                event.occurredAt(), event.receivedAt(), "mine-telemetry",
                event.eventId().toString(), event.tagId().toString(), UUID.randomUUID().toString(),
                "mine-local", event);
        Message message = message(invalid, 62L);

        consumer.onMessage(message, channel);

        verify(channel).basicReject(62L, false);
        verify(registry, never()).publishLocation(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void Rabbit消息头版本不匹配时应拒绝() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeLocationConsumer consumer = new RealtimeLocationConsumer(registry);
        RealtimeLocationEvent event = event();
        Message message = message(envelope(event), 63L);
        message.getMessageProperties().setHeader("schemaVersion", 2);

        consumer.onMessage(message, channel);

        verify(channel).basicReject(63L, false);
        verify(registry, never()).publishLocation(org.mockito.ArgumentMatchers.any());
    }

    private MessageEnvelope<RealtimeLocationEvent> envelope(RealtimeLocationEvent event) {
        return new MessageEnvelope<>(
                event.eventId(), MessageType.of("realtime.location.updated"), 1,
                event.occurredAt(), event.receivedAt(), "mine-telemetry",
                event.eventId().toString(), event.tagId().toString(), event.siteId().toString(),
                "mine-local", event);
    }

    private Message message(MessageEnvelope<RealtimeLocationEvent> envelope, long deliveryTag)
            throws Exception {
        Message message = MessageBuilder.withBody(jsonMapper.writeValueAsBytes(envelope))
                .setHeader("eventType", "realtime.location.updated")
                .setHeader("schemaVersion", 1)
                .setHeader("siteId", envelope.payload().siteId().toString())
                .build();
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
    }

    private RealtimeLocationEvent event() {
        return new RealtimeLocationEvent(
                UUID.fromString("0198aef6-2e80-7000-8000-000000000601"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000602"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000603"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000604"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000605"),
                "tag-001",
                UUID.fromString("0198aef6-2e80-7000-8000-000000000606"),
                null, null, 81L,
                Instant.parse("2026-07-15T02:00:00Z"),
                Instant.parse("2026-07-15T02:00:01Z"),
                10D, 20D, null, 3D, 90D, 0.5D,
                RealtimeLocationEvent.QualityStatus.GOOD, null);
    }
}
