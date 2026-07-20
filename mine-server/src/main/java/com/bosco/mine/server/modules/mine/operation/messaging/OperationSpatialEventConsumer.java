package com.bosco.mine.server.modules.mine.operation.messaging;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.modules.mine.operation.runtime.OperationEventQueue;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;

/**
 * 校验并持久接收空间业务事件，在数据库事务提交后手工确认 RabbitMQ 消息。
 */
public final class OperationSpatialEventConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            OperationSpatialEventConsumer.class);
    private static final String EVENT_TYPE = "realtime.spatial.event.created";
    private static final int MAX_MESSAGE_BYTES = 64 * 1024;
    private static final TypeReference<MessageEnvelope<RealtimeSpatialEvent>> ENVELOPE_TYPE =
            new TypeReference<>() { };

    private final OperationEventQueue queue;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建运营空间事件消费者。
     *
     * @param queue 事务性持久事件队列
     */
    public OperationSpatialEventConsumer(OperationEventQueue queue) {
        this.queue = Objects.requireNonNull(queue, "运营空间事件队列不能为空");
    }

    /**
     * 合法事件事务提交后确认，永久契约错误拒绝，基础设施异常重新入队。
     *
     * @param message RabbitMQ 消息
     * @param channel RabbitMQ 通道
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        Objects.requireNonNull(message, "运营空间消息不能为空");
        Objects.requireNonNull(channel, "RabbitMQ 通道不能为空");
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            queue.enqueue(decode(message));
            channel.basicAck(deliveryTag, false);
        } catch (InvalidSpatialMessageException
                 | OperationEventQueue.IdentityConflictException exception) {
            LOGGER.warn("拒绝不可重试的运营空间事件: {}", exception.getMessage());
            channel.basicReject(deliveryTag, false);
        }
    }

    private RealtimeSpatialEvent decode(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0 || body.length > MAX_MESSAGE_BYTES) {
            throw new InvalidSpatialMessageException("运营空间事件消息大小不合法");
        }
        try {
            MessageEnvelope<RealtimeSpatialEvent> envelope =
                    jsonMapper.readValue(body, ENVELOPE_TYPE);
            RealtimeSpatialEvent event = envelope.payload();
            Map<String, Object> headers = message.getMessageProperties().getHeaders();
            Object schemaHeader = headers.get("schemaVersion");
            String expectedRoutingKey = MineTopology.REALTIME_SPATIAL_ROUTING_PREFIX
                    + event.siteId();
            if (!expectedRoutingKey.equals(
                    message.getMessageProperties().getReceivedRoutingKey())
                    || !EVENT_TYPE.equals(envelope.eventType().value())
                    || envelope.schemaVersion() != 1
                    || !"mine-telemetry".equals(envelope.producer())
                    || !"mine-local".equals(envelope.tenantScope())
                    || !envelope.eventId().equals(event.eventId())
                    || !envelope.occurredAt().equals(event.occurredAt())
                    || !event.anchorLocationEventId().toString().equals(envelope.traceId())
                    || !event.zoneId().toString().equals(envelope.correlationId())
                    || !event.siteId().toString().equals(envelope.partitionKey())
                    || !EVENT_TYPE.equals(headers.get("eventType"))
                    || !(schemaHeader instanceof Number number)
                    || number.intValue() != 1
                    || !event.siteId().toString().equals(headers.get("siteId"))
                    || !event.zoneType().name().equals(headers.get("zoneType"))) {
                throw new InvalidSpatialMessageException(
                        "运营空间事件信封、消息头与载荷身份不一致");
            }
            return event;
        } catch (InvalidSpatialMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidSpatialMessageException("运营空间事件 JSON 无法解析", exception);
        }
    }

    /** 表示不可重试且不携带原始载荷的空间消息错误。 */
    private static final class InvalidSpatialMessageException extends IllegalArgumentException {
        /** 创建固定契约错误。 */
        private InvalidSpatialMessageException(String message) {
            super(message);
        }

        /** 创建固定解析错误并保留内部原因。 */
        private InvalidSpatialMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
