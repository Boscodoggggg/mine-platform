package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * 消费已持久化的位置实时消息信封并分发给本实例矿区订阅。
 */
public final class RealtimeLocationConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeLocationConsumer.class);
    private static final String EVENT_TYPE = "realtime.location.updated";
    private static final TypeReference<MessageEnvelope<RealtimeLocationEvent>> ENVELOPE_TYPE =
            new TypeReference<>() { };

    private final RealtimeSessionRegistry registry;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建位置实时消费者。
     *
     * @param registry 本实例实时会话注册表
     */
    public RealtimeLocationConsumer(RealtimeSessionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "实时会话注册表不能为空");
    }

    /**
     * 分发合法消息后确认；契约错误拒绝，暂时分发失败重新入队。
     *
     * @param message RabbitMQ 消息
     * @param channel 消费通道
     */
    @Override
    public void onMessage(Message message, Channel channel) {
        Objects.requireNonNull(message, "位置实时消息不能为空");
        Objects.requireNonNull(channel, "RabbitMQ 通道不能为空");
        try {
            RealtimeLocationEvent event = decode(message);
            registry.publishLocation(event);
            channel.basicAck(deliveryTag(message), false);
        } catch (PermanentLocationMessageException exception) {
            LOGGER.warn("拒绝不可解析的位置实时事件: {}", exception.getMessage());
            reject(message, channel);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        } catch (RuntimeException exception) {
            LOGGER.error("位置实时事件分发失败，消息将重新入队", exception);
            nack(message, channel);
        }
    }

    private RealtimeLocationEvent decode(Message message) {
        try {
            MessageEnvelope<RealtimeLocationEvent> envelope =
                    jsonMapper.readValue(message.getBody(), ENVELOPE_TYPE);
            RealtimeLocationEvent payload = envelope.payload();
            Map<String, Object> headers = message.getMessageProperties().getHeaders();
            Object schemaHeader = headers.get("schemaVersion");
            if (!EVENT_TYPE.equals(envelope.eventType().value())
                    || envelope.schemaVersion() != 1
                    || !"mine-telemetry".equals(envelope.producer())
                    || !"mine-local".equals(envelope.tenantScope())
                    || !envelope.eventId().equals(payload.eventId())
                    || !envelope.occurredAt().equals(payload.occurredAt())
                    || !payload.tagId().toString().equals(envelope.correlationId())
                    || !payload.siteId().toString().equals(envelope.partitionKey())
                    || !EVENT_TYPE.equals(headers.get("eventType"))
                    || !(schemaHeader instanceof Number number)
                    || number.intValue() != 1
                    || !payload.siteId().toString().equals(headers.get("siteId"))) {
                throw new PermanentLocationMessageException("位置实时消息信封与载荷不一致");
            }
            return payload;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            if (exception instanceof PermanentLocationMessageException permanent) {
                throw permanent;
            }
            throw new PermanentLocationMessageException("位置实时消息格式非法", exception);
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

    /**
     * 表示不可重试的位置实时消息契约错误。
     */
    private static final class PermanentLocationMessageException extends IllegalArgumentException {

        /** 创建不带原始异常的契约错误。 */
        private PermanentLocationMessageException(String message) {
            super(message);
        }

        /** 创建保留原始异常的契约错误。 */
        private PermanentLocationMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
