package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.realtime.RealtimeOperationEvent;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 校验运营实时消息信封、事件幂等和瞬时分发失败重投语义。
 */
class RealtimeOperationConsumerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 合法运营消息必须先分发再确认且重复事件只确认一次分发() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeOperationConsumer consumer = new RealtimeOperationConsumer(registry);
        RealtimeOperationEvent event = event(1, 17L);

        consumer.onMessage(message(envelope(event), 81L), channel);
        consumer.onMessage(message(envelope(event), 82L), channel);

        verify(registry).publishOperation(event);
        verify(channel).basicAck(81L, false);
        verify(channel).basicAck(82L, false);
    }

    @Test
    void 信封矿区或业务序号不一致必须永久拒绝() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeOperationConsumer consumer = new RealtimeOperationConsumer(registry);
        RealtimeOperationEvent event = event(2, 18L);
        MessageEnvelope<RealtimeOperationEvent> invalid = new MessageEnvelope<>(
                event.eventId(), MessageType.of("realtime.operation.updated"), 1,
                event.occurredAt(), event.occurredAt().plusSeconds(1), "mine-server",
                event.eventId().toString(), event.aggregateId().toString(),
                uuid(99).toString(), "mine-local", event);

        consumer.onMessage(message(invalid, 83L), channel);

        verify(channel).basicReject(83L, false);
        verify(registry, never()).publishOperation(event);
    }

    @Test
    void 分发瞬时失败后重投不得被提前记为重复() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeOperationConsumer consumer = new RealtimeOperationConsumer(registry);
        RealtimeOperationEvent event = event(3, 19L);
        doThrow(new IllegalStateException("连接瞬时不可用"))
                .doNothing()
                .when(registry).publishOperation(event);

        consumer.onMessage(message(envelope(event), 84L), channel);
        consumer.onMessage(message(envelope(event), 85L), channel);

        verify(registry, times(2)).publishOperation(event);
        verify(channel).basicNack(84L, false, true);
        verify(channel).basicAck(85L, false);
    }

    @Test
    void 超大消息必须在反序列化前永久拒绝() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeOperationConsumer consumer = new RealtimeOperationConsumer(registry);
        Message oversized = new Message(new byte[8193]);
        oversized.getMessageProperties().setDeliveryTag(86L);

        consumer.onMessage(oversized, channel);

        verify(channel).basicReject(86L, false);
        verify(registry, never()).publishOperation(org.mockito.ArgumentMatchers.any());
    }

    private MessageEnvelope<RealtimeOperationEvent> envelope(RealtimeOperationEvent event) {
        return new MessageEnvelope<>(
                event.eventId(), MessageType.of("realtime.operation.updated"), 1,
                event.occurredAt(), event.occurredAt().plusSeconds(1), "mine-server",
                event.eventId().toString(), event.aggregateId().toString(),
                event.siteId().toString(), "mine-local", event);
    }

    private Message message(
            MessageEnvelope<RealtimeOperationEvent> envelope,
            long deliveryTag
    ) throws Exception {
        Message message = MessageBuilder.withBody(jsonMapper.writeValueAsBytes(envelope))
                .setHeader("eventType", "realtime.operation.updated")
                .setHeader("schemaVersion", 1)
                .setHeader("siteId", envelope.payload().siteId().toString())
                .setHeader("updateType", envelope.payload().updateType())
                .setHeader("businessSequence", envelope.payload().businessSequence())
                .build();
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
    }

    private RealtimeOperationEvent event(int suffix, long sequence) {
        return new RealtimeOperationEvent(
                uuid(suffix), uuid(10), uuid(11),
                RealtimeOperationEvent.AggregateType.OPERATION_RECORD,
                uuid(20), "RECORD_COMPLETED", sequence,
                Instant.parse("2026-07-20T05:00:00Z").plusSeconds(suffix),
                Map.of("operationRecordId", uuid(20).toString(), "effective", true));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7d00-0000-7000-8000-%012d".formatted(suffix));
    }
}
