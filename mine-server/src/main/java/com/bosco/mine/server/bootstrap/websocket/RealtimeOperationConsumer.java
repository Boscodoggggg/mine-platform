package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.realtime.RealtimeOperationEvent;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 校验运营实时事件并在本实例内按事件标识去重后推送 WebSocket。
 */
public final class RealtimeOperationConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeOperationConsumer.class);
    private static final String EVENT_TYPE = "realtime.operation.updated";
    private static final int MAX_MESSAGE_BYTES = 8192;
    private static final int RECENT_EVENT_CAPACITY = 50_000;
    private static final TypeReference<MessageEnvelope<RealtimeOperationEvent>> ENVELOPE_TYPE =
            new TypeReference<>() { };

    private final RealtimeSessionRegistry registry;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final LinkedHashMap<UUID, Boolean> recentEventIds = new LinkedHashMap<>();

    /**
     * 创建运营实时消费者。
     *
     * @param registry 本实例实时会话注册表
     */
    public RealtimeOperationConsumer(RealtimeSessionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "实时会话注册表不能为空");
    }

    /** 合法新事件先分发再确认，契约错误永久拒绝，瞬时错误重新入队。 */
    @Override
    public void onMessage(Message message, Channel channel) {
        Objects.requireNonNull(message, "运营实时消息不能为空");
        Objects.requireNonNull(channel, "RabbitMQ 通道不能为空");
        try {
            RealtimeOperationEvent event = decode(message);
            publishIfNew(event);
            channel.basicAck(deliveryTag(message), false);
        } catch (PermanentOperationMessageException exception) {
            LOGGER.warn("拒绝不可解析的运营实时事件: {}", exception.getMessage());
            reject(message, channel);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        } catch (RuntimeException exception) {
            LOGGER.error("运营实时事件分发失败，消息将重新入队", exception);
            nack(message, channel);
        }
    }

    private RealtimeOperationEvent decode(Message message) {
        if (message.getBody().length > MAX_MESSAGE_BYTES) {
            throw new PermanentOperationMessageException("运营实时消息超过大小上限");
        }
        try {
            MessageEnvelope<RealtimeOperationEvent> envelope =
                    jsonMapper.readValue(message.getBody(), ENVELOPE_TYPE);
            RealtimeOperationEvent payload = envelope.payload();
            Map<String, Object> headers = message.getMessageProperties().getHeaders();
            Object schemaHeader = headers.get("schemaVersion");
            Object sequenceHeader = headers.get("businessSequence");
            if (!EVENT_TYPE.equals(envelope.eventType().value())
                    || envelope.schemaVersion() != 1
                    || !"mine-server".equals(envelope.producer())
                    || !"mine-local".equals(envelope.tenantScope())
                    || !envelope.eventId().equals(payload.eventId())
                    || !envelope.occurredAt().equals(payload.occurredAt())
                    || !payload.eventId().toString().equals(envelope.traceId())
                    || !payload.aggregateId().toString().equals(envelope.correlationId())
                    || !payload.siteId().toString().equals(envelope.partitionKey())
                    || !EVENT_TYPE.equals(headers.get("eventType"))
                    || !(schemaHeader instanceof Number schema)
                    || schema.intValue() != 1
                    || !payload.siteId().toString().equals(headers.get("siteId"))
                    || !payload.updateType().equals(headers.get("updateType"))
                    || !(sequenceHeader instanceof Number sequence)
                    || sequence.longValue() != payload.businessSequence()) {
                throw new PermanentOperationMessageException(
                        "运营实时消息信封与载荷不一致");
            }
            return payload;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            if (exception instanceof PermanentOperationMessageException permanent) {
                throw permanent;
            }
            throw new PermanentOperationMessageException(
                    "运营实时消息格式非法", exception);
        }
    }

    private void publishIfNew(RealtimeOperationEvent event) {
        synchronized (recentEventIds) {
            if (recentEventIds.containsKey(event.eventId())) {
                return;
            }
            registry.publishOperation(event);
            recentEventIds.put(event.eventId(), Boolean.TRUE);
            if (recentEventIds.size() > RECENT_EVENT_CAPACITY) {
                UUID eldest = recentEventIds.keySet().iterator().next();
                recentEventIds.remove(eldest);
            }
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

    /** 不可重试的运营实时消息契约错误。 */
    private static final class PermanentOperationMessageException
            extends IllegalArgumentException {

        /** 创建不带原始异常的契约错误。 */
        private PermanentOperationMessageException(String message) {
            super(message);
        }

        /** 创建保留原始异常的契约错误。 */
        private PermanentOperationMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
