package com.bosco.mine.server.bootstrap.websocket;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.io.IOException;
import java.util.Objects;

/**
 * 按 RabbitMQ 可信路由键将统一临时队列中的事件交给强类型消费者。
 */
public final class RealtimeEventConsumer implements ChannelAwareMessageListener {

    private final RealtimeTelemetryConsumer telemetryConsumer;
    private final RealtimeLocationConsumer locationConsumer;

    /**
     * 创建统一实时事件分派器。
     *
     * @param telemetryConsumer 遥测实时消费者
     * @param locationConsumer 位置实时消费者
     */
    public RealtimeEventConsumer(
            RealtimeTelemetryConsumer telemetryConsumer,
            RealtimeLocationConsumer locationConsumer
    ) {
        this.telemetryConsumer = Objects.requireNonNull(
                telemetryConsumer, "遥测实时消费者不能为空");
        this.locationConsumer = Objects.requireNonNull(
                locationConsumer, "位置实时消费者不能为空");
    }

    /**
     * 根据实际接收路由键分派消息，未知路由不可重试。
     *
     * @param message RabbitMQ 消息
     * @param channel 消费通道
     */
    @Override
    public void onMessage(Message message, Channel channel) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (routingKey != null && routingKey.startsWith("realtime.telemetry.")) {
            telemetryConsumer.onMessage(message, channel);
            return;
        }
        if (routingKey != null && routingKey.startsWith("realtime.location.")) {
            locationConsumer.onMessage(message, channel);
            return;
        }
        try {
            channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        }
    }
}
