package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 校验空间实时事件信封白名单、事件标识幂等和合法乱序不丢失。 */
class RealtimeSpatialConsumerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 合法空间消息应先分发再确认() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeSpatialConsumer consumer = new RealtimeSpatialConsumer(registry);
        RealtimeSpatialEvent event = event(1, 81L, uuid(20));
        Message message = message(envelope(event), 71L);

        consumer.onMessage(message, channel);

        var order = inOrder(registry, channel);
        order.verify(registry).publishSpatial(event);
        order.verify(channel).basicAck(71L, false);
    }

    @Test
    void 同矿区乱序事件仍应推送而重复事件标识不得再次推送() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeSpatialConsumer consumer = new RealtimeSpatialConsumer(registry);
        RealtimeSpatialEvent newest = event(2, 92L, uuid(20));
        RealtimeSpatialEvent older = event(3, 91L, uuid(20));
        Message newestMessage = message(envelope(newest), 72L);
        Message duplicateMessage = message(envelope(newest), 73L);
        Message olderMessage = message(envelope(older), 74L);

        consumer.onMessage(newestMessage, channel);
        consumer.onMessage(duplicateMessage, channel);
        consumer.onMessage(olderMessage, channel);

        verify(registry).publishSpatial(newest);
        verify(registry).publishSpatial(older);
        verify(channel).basicAck(72L, false);
        verify(channel).basicAck(73L, false);
        verify(channel).basicAck(74L, false);
    }

    @Test
    void 信封区域与载荷不一致时应永久拒绝() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeSpatialConsumer consumer = new RealtimeSpatialConsumer(registry);
        RealtimeSpatialEvent event = event(4, 93L, uuid(20));
        MessageEnvelope<RealtimeSpatialEvent> invalid = new MessageEnvelope<>(
                event.eventId(), MessageType.of("realtime.spatial.event.created"), 1,
                event.occurredAt(), event.occurredAt().plusSeconds(1), "mine-telemetry",
                event.anchorLocationEventId().toString(), UUID.randomUUID().toString(),
                event.siteId().toString(), "mine-local", event);
        Message message = message(invalid, 75L);

        consumer.onMessage(message, channel);

        verify(channel).basicReject(75L, false);
        verify(registry, never()).publishSpatial(event);
    }

    @Test
    void 分发瞬时失败后重投不得被提前提交的水位吞掉() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeSpatialConsumer consumer = new RealtimeSpatialConsumer(registry);
        RealtimeSpatialEvent event = event(5, 94L, uuid(20));
        doThrow(new IllegalStateException("连接瞬时不可用"))
                .doNothing()
                .when(registry).publishSpatial(event);

        consumer.onMessage(message(envelope(event), 76L), channel);
        consumer.onMessage(message(envelope(event), 77L), channel);

        verify(registry, times(2)).publishSpatial(event);
        verify(channel).basicNack(76L, false, true);
        verify(channel).basicAck(77L, false);
    }

    private MessageEnvelope<RealtimeSpatialEvent> envelope(RealtimeSpatialEvent event) {
        return new MessageEnvelope<>(
                event.eventId(), MessageType.of("realtime.spatial.event.created"), 1,
                event.occurredAt(), event.occurredAt().plusSeconds(1), "mine-telemetry",
                event.anchorLocationEventId().toString(), event.zoneId().toString(),
                event.siteId().toString(), "mine-local", event);
    }

    private Message message(MessageEnvelope<RealtimeSpatialEvent> envelope, long deliveryTag)
            throws Exception {
        Message message = MessageBuilder.withBody(jsonMapper.writeValueAsBytes(envelope))
                .setHeader("eventType", "realtime.spatial.event.created")
                .setHeader("schemaVersion", 1)
                .setHeader("siteId", envelope.payload().siteId().toString())
                .setHeader("zoneType", envelope.payload().zoneType().name())
                .build();
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
    }

    private RealtimeSpatialEvent event(int suffix, long sequence, UUID zoneVersionId) {
        return new RealtimeSpatialEvent(
                uuid(suffix), uuid(10), uuid(11), RealtimeSpatialEvent.ZoneType.GEOFENCE,
                uuid(12), zoneVersionId, uuid(13), uuid(14), null,
                RealtimeSpatialEvent.EventType.ENTER, uuid(100 + suffix),
                RealtimeSpatialEvent.StableState.OUTSIDE,
                RealtimeSpatialEvent.StableState.INSIDE, 2, sequence, 7,
                Instant.parse("2026-07-20T00:00:00Z").plusSeconds(sequence),
                new RealtimeSpatialEvent.Evidence(
                        120.5D, 88.25D, null, "INSIDE", "GOOD", null,
                        "sha256:" + "a".repeat(64)));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7c00-0000-7000-8000-%012d".formatted(suffix));
    }
}
