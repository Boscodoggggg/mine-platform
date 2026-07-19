package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
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
 * 校验并消费已提交空间事件，以有界事件标识缓存阻止重复且不丢弃合法乱序事件。
 */
public final class RealtimeSpatialConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeSpatialConsumer.class);
    private static final String EVENT_TYPE = "realtime.spatial.event.created";
    private static final int RECENT_EVENT_CAPACITY = 50_000;
    private static final TypeReference<MessageEnvelope<RealtimeSpatialEvent>> ENVELOPE_TYPE =
            new TypeReference<>() { };

    private final RealtimeSessionRegistry registry;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final LinkedHashMap<UUID, Boolean> recentEventIds = new LinkedHashMap<>();

    /**
     * 创建空间事件消费者。
     *
     * @param registry 本实例实时会话注册表
     */
    public RealtimeSpatialConsumer(RealtimeSessionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "实时会话注册表不能为空");
    }

    /**
     * 合法新事件先分发再确认；重复和旧事件直接确认；契约错误永久拒绝。
     *
     * @param message RabbitMQ 消息
     * @param channel 消费通道
     */
    @Override
    public void onMessage(Message message, Channel channel) {
        Objects.requireNonNull(message, "空间实时消息不能为空");
        Objects.requireNonNull(channel, "RabbitMQ 通道不能为空");
        try {
            RealtimeSpatialEvent event = decode(message);
            publishIfNew(event);
            channel.basicAck(deliveryTag(message), false);
        } catch (PermanentSpatialMessageException exception) {
            LOGGER.warn("拒绝不可解析的空间实时事件: {}", exception.getMessage());
            reject(message, channel);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        } catch (RuntimeException exception) {
            LOGGER.error("空间实时事件分发失败，消息将重新入队", exception);
            nack(message, channel);
        }
    }

    private RealtimeSpatialEvent decode(Message message) {
        try {
            MessageEnvelope<RealtimeSpatialEvent> envelope =
                    jsonMapper.readValue(message.getBody(), ENVELOPE_TYPE);
            RealtimeSpatialEvent payload = envelope.payload();
            Map<String, Object> headers = message.getMessageProperties().getHeaders();
            Object schemaHeader = headers.get("schemaVersion");
            if (!EVENT_TYPE.equals(envelope.eventType().value())
                    || envelope.schemaVersion() != 1
                    || !"mine-telemetry".equals(envelope.producer())
                    || !"mine-local".equals(envelope.tenantScope())
                    || !envelope.eventId().equals(payload.eventId())
                    || !envelope.occurredAt().equals(payload.occurredAt())
                    || !payload.anchorLocationEventId().toString().equals(envelope.traceId())
                    || !payload.zoneId().toString().equals(envelope.correlationId())
                    || !payload.siteId().toString().equals(envelope.partitionKey())
                    || !EVENT_TYPE.equals(headers.get("eventType"))
                    || !(schemaHeader instanceof Number number)
                    || number.intValue() != 1
                    || !payload.siteId().toString().equals(headers.get("siteId"))
                    || !payload.zoneType().name().equals(headers.get("zoneType"))) {
                throw new PermanentSpatialMessageException("空间实时消息信封与载荷不一致");
            }
            return payload;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            if (exception instanceof PermanentSpatialMessageException permanent) {
                throw permanent;
            }
            throw new PermanentSpatialMessageException("空间实时消息格式非法", exception);
        }
    }

    private void publishIfNew(RealtimeSpatialEvent event) {
        synchronized (recentEventIds) {
            if (recentEventIds.containsKey(event.eventId())) {
                return;
            }
            registry.publishSpatial(event);
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

    /** 表示不可重试的空间实时消息契约错误。 */
    private static final class PermanentSpatialMessageException extends IllegalArgumentException {

        /** 创建不带原始异常的契约错误。 */
        private PermanentSpatialMessageException(String message) {
            super(message);
        }

        /** 创建保留原始异常的契约错误。 */
        private PermanentSpatialMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
