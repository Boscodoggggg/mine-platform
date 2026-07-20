package com.bosco.mine.server.bootstrap.websocket;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 校验统一临时队列严格按实际 RabbitMQ 路由键分派强类型事件。
 */
class RealtimeEventConsumerTest {

    @Test
    void 应将遥测位置空间和运营事件交给各自消费者() {
        RealtimeTelemetryConsumer telemetry = mock(RealtimeTelemetryConsumer.class);
        RealtimeLocationConsumer location = mock(RealtimeLocationConsumer.class);
        RealtimeSpatialConsumer spatial = mock(RealtimeSpatialConsumer.class);
        RealtimeOperationConsumer operation = mock(RealtimeOperationConsumer.class);
        Channel channel = mock(Channel.class);
        RealtimeEventConsumer consumer = new RealtimeEventConsumer(
                telemetry, location, spatial, operation);
        Message telemetryMessage = message("realtime.telemetry.device-a", 91L);
        Message locationMessage = message("realtime.location.site-a", 92L);
        Message spatialMessage = message("realtime.spatial.site-a", 93L);
        Message operationMessage = message("realtime.operation.site-a", 94L);

        consumer.onMessage(telemetryMessage, channel);
        consumer.onMessage(locationMessage, channel);
        consumer.onMessage(spatialMessage, channel);
        consumer.onMessage(operationMessage, channel);

        verify(telemetry).onMessage(telemetryMessage, channel);
        verify(location).onMessage(locationMessage, channel);
        verify(spatial).onMessage(spatialMessage, channel);
        verify(operation).onMessage(operationMessage, channel);
    }

    @Test
    void 未知路由应拒绝且不得猜测消息类型() throws Exception {
        RealtimeTelemetryConsumer telemetry = mock(RealtimeTelemetryConsumer.class);
        RealtimeLocationConsumer location = mock(RealtimeLocationConsumer.class);
        RealtimeSpatialConsumer spatial = mock(RealtimeSpatialConsumer.class);
        RealtimeOperationConsumer operation = mock(RealtimeOperationConsumer.class);
        Channel channel = mock(Channel.class);
        RealtimeEventConsumer consumer = new RealtimeEventConsumer(
                telemetry, location, spatial, operation);
        Message message = message("realtime.unknown.value", 95L);

        consumer.onMessage(message, channel);

        verify(channel).basicReject(95L, false);
        verify(telemetry, never()).onMessage(message, channel);
        verify(location, never()).onMessage(message, channel);
        verify(spatial, never()).onMessage(message, channel);
        verify(operation, never()).onMessage(message, channel);
    }

    private Message message(String routingKey, long deliveryTag) {
        Message message = new Message(new byte[0]);
        message.getMessageProperties().setReceivedRoutingKey(routingKey);
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
    }
}
