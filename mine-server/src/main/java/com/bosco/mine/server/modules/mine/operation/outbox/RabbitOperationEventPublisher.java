package com.bosco.mine.server.modules.mine.operation.outbox;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.realtime.RealtimeOperationEvent;
import com.bosco.mine.messaging.topology.MineTopology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 将已领取运营 Outbox 转为强类型实时事件并等待 RabbitMQ 发布确认。
 */
public final class RabbitOperationEventPublisher implements OperationOutboxRelay.Publisher {

    private static final String EVENT_TYPE = "realtime.operation.updated";
    private static final int MAX_MESSAGE_BYTES = 8192;

    private final RabbitOperations rabbitOperations;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建运营实时事件发布器。
     *
     * @param rabbitOperations RabbitMQ 操作器
     * @param confirmTimeoutMillis 发布确认超时毫秒数
     */
    public RabbitOperationEventPublisher(
            RabbitOperations rabbitOperations,
            long confirmTimeoutMillis
    ) {
        this.rabbitOperations = Objects.requireNonNull(
                rabbitOperations, "RabbitMQ 操作器不能为空");
        if (confirmTimeoutMillis < 1) {
            throw new IllegalArgumentException("运营事件发布确认超时必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /** 发布持久、分区明确且有界的运营实时事件。 */
    @Override
    public void publish(OperationOutboxRelay.ClaimedEvent event) {
        Objects.requireNonNull(event, "已领取运营事件不能为空");
        if (!EVENT_TYPE.equals(event.eventType()) || event.schemaVersion() != 1) {
            throw new IllegalArgumentException("运营 Outbox 事件类型或版本不受支持");
        }
        Map<String, Object> summary = readSummary(event.payloadJson());
        String updateType = requiredText(summary, "updateType");
        String payloadSiteId = requiredText(summary, "siteId");
        if (!event.siteId().toString().equals(payloadSiteId)) {
            throw new IllegalArgumentException("运营 Outbox 作用域与摘要矿区不一致");
        }
        RealtimeOperationEvent payload = new RealtimeOperationEvent(
                event.eventId(), event.organizationId(), event.siteId(),
                RealtimeOperationEvent.AggregateType.valueOf(event.aggregateType()),
                event.aggregateId(), updateType, event.businessSequence(),
                event.createdAt(), summary);
        MessageEnvelope<RealtimeOperationEvent> envelope = new MessageEnvelope<>(
                event.eventId(), MessageType.of(EVENT_TYPE), 1,
                event.createdAt(), Instant.now(), "mine-server",
                event.eventId().toString(), event.aggregateId().toString(),
                event.siteId().toString(), "mine-local", payload);
        byte[] body = write(envelope);
        if (body.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("运营实时消息超过大小上限");
        }
        Message message = MessageBuilder.withBody(body)
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.eventId().toString())
                .setHeader("eventType", EVENT_TYPE)
                .setHeader("schemaVersion", 1)
                .setHeader("siteId", event.siteId().toString())
                .setHeader("aggregateType", event.aggregateType())
                .setHeader("aggregateId", event.aggregateId().toString())
                .setHeader("updateType", updateType)
                .setHeader("businessSequence", event.businessSequence())
                .build();
        rabbitOperations.invoke(scoped -> {
            scoped.send(
                    MineTopology.REALTIME_EXCHANGE,
                    MineTopology.REALTIME_OPERATION_ROUTING_PREFIX + event.siteId(),
                    message);
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private Map<String, Object> readSummary(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("运营 Outbox 摘要 JSON 无法解析", exception);
        }
    }

    private String requiredText(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("运营 Outbox 摘要缺少字段: " + name);
        }
        return text;
    }

    private byte[] write(MessageEnvelope<RealtimeOperationEvent> envelope) {
        try {
            return jsonMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("运营实时消息信封无法序列化", exception);
        }
    }
}
