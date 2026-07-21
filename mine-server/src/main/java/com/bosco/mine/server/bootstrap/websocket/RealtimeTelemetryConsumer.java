package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;

/**
 * 消费已持久化实时遥测事件并分发给本实例内存订阅。
 */
public final class RealtimeTelemetryConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeTelemetryConsumer.class);

    private final RealtimeSessionRegistry registry;
    private final JsonMapper jsonMapper;

    /**
     * 创建实时遥测消费者。
     *
     * @param registry 本实例实时会话注册表
     */
    public RealtimeTelemetryConsumer(RealtimeSessionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "实时会话注册表不能为空");
        this.jsonMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * 分发合法事件后手工确认；非法消息直接拒绝，临时分发失败重新入队。
     *
     * @param message RabbitMQ 消息
     * @param channel 消费通道
     */
    @Override
    public void onMessage(Message message, Channel channel) {
        try {
            RealtimeTelemetryEvent event = jsonMapper.readValue(
                    message.getBody(), RealtimeTelemetryEvent.class
            );
            registry.publish(event);
            channel.basicAck(deliveryTag(message), false);
        } catch (JacksonException | IllegalArgumentException exception) {
            LOGGER.warn("拒绝不可解析的实时遥测事件: {}", exception.getMessage());
            reject(message, channel);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        } catch (RuntimeException exception) {
            LOGGER.error("实时遥测分发失败，消息将重新入队", exception);
            nack(message, channel);
        }
    }

    private void reject(Message message, Channel channel) {
        try {
            channel.basicReject(deliveryTag(message), false);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        }
    }

    private void nack(Message message, Channel channel) {
        try {
            channel.basicNack(deliveryTag(message), false, true);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        }
    }

    private long deliveryTag(Message message) {
        return message.getMessageProperties().getDeliveryTag();
    }
}
