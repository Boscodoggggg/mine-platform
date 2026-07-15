package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验 server 只消费实时事件并分发给内存订阅，不查询业务表。
 */
class RealtimeTelemetryConsumerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 合法实时事件应先分发再确认RabbitMQ消息() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeTelemetryConsumer consumer = new RealtimeTelemetryConsumer(registry);
        RealtimeTelemetryEvent event = event();
        Message message = new Message(jsonMapper.writeValueAsBytes(event));
        message.getMessageProperties().setDeliveryTag(51L);

        consumer.onMessage(message, channel);

        var order = inOrder(registry, channel);
        order.verify(registry).publish(event);
        order.verify(channel).basicAck(51L, false);
    }

    @Test
    void 非法实时事件应拒绝且不重新入队() throws Exception {
        RealtimeSessionRegistry registry = mock(RealtimeSessionRegistry.class);
        Channel channel = mock(Channel.class);
        RealtimeTelemetryConsumer consumer = new RealtimeTelemetryConsumer(registry);
        Message message = new Message("invalid-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        message.getMessageProperties().setDeliveryTag(52L);

        consumer.onMessage(message, channel);

        verify(channel).basicReject(52L, false);
        verify(registry, org.mockito.Mockito.never()).publish(org.mockito.ArgumentMatchers.any());
    }

    private RealtimeTelemetryEvent event() {
        return new RealtimeTelemetryEvent(
                UUID.fromString("0198aef6-2e80-7000-8000-000000000301"),
                UUID.fromString("0198aef6-2e80-7000-8000-000000000101"),
                42L,
                Instant.parse("2026-07-15T02:00:00Z"),
                Instant.parse("2026-07-15T02:00:01Z"),
                Quality.GOOD,
                "boot-a",
                8L,
                Map.of("speed", 13.5D)
        );
    }
}
