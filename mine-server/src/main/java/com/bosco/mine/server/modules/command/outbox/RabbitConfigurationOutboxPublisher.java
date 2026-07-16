package com.bosco.mine.server.modules.command.outbox;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.configuration.ConfigReleasePayload;
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
 * 将配置发布 Outbox 事件发送到配置交换机并等待 Broker 确认。
 */
public final class RabbitConfigurationOutboxPublisher implements OutboxEventPublisher {

    private final RabbitOperations rabbitOperations;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建配置发布事件发布器。
     *
     * @param rabbitOperations RabbitMQ 操作器
     * @param confirmTimeoutMillis 发布确认超时毫秒数
     */
    public RabbitConfigurationOutboxPublisher(
            RabbitOperations rabbitOperations,
            long confirmTimeoutMillis
    ) {
        this.rabbitOperations = Objects.requireNonNull(rabbitOperations, "RabbitMQ 操作器不能为空");
        if (confirmTimeoutMillis < 1) {
            throw new IllegalArgumentException("发布确认超时必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /**
     * 发布轻量配置事件，不在消息总线复制完整快照。
     *
     * @param event 已认领事件
     */
    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "Outbox 事件不能为空");
        ConfigReleasePayload payload = readPayload(event.payloadJson());
        Map<String, Object> headers = readHeaders(event.headersJson());
        String partitionKey = requiredHeader(headers, "partitionKey");
        String traceId = requiredHeader(headers, "traceId");
        if (!payload.releaseId().equals(event.aggregateId())
                || !payload.productId().toString().equals(partitionKey)) {
            throw new IllegalArgumentException("配置发布事件聚合标识或产品分区键不一致");
        }
        MessageEnvelope<ConfigReleasePayload> envelope = new MessageEnvelope<>(
                event.eventId(), MessageType.of(event.eventType()), event.schemaVersion(),
                event.occurredAt(), Instant.now(), "mine-server", traceId,
                payload.releaseId().toString(), partitionKey, "mine-local", payload
        );
        Message message = MessageBuilder.withBody(writeEnvelope(envelope))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.eventId().toString())
                .setHeader("eventType", event.eventType())
                .setHeader("schemaVersion", event.schemaVersion())
                .setHeader("aggregateId", event.aggregateId().toString())
                .build();
        rabbitOperations.invoke(scoped -> {
            scoped.send(
                    MineTopology.CONFIG_EXCHANGE,
                    "config.release." + partitionKey,
                    message
            );
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private ConfigReleasePayload readPayload(String json) {
        try {
            return jsonMapper.readValue(json, ConfigReleasePayload.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("配置发布事件载荷 JSON 无法解析", exception);
        }
    }

    private Map<String, Object> readHeaders(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("配置发布事件头 JSON 无法解析", exception);
        }
    }

    private String requiredHeader(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("配置发布事件缺少消息头: " + name);
        }
        return text;
    }

    private byte[] writeEnvelope(MessageEnvelope<ConfigReleasePayload> envelope) {
        try {
            return jsonMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("配置发布消息信封无法序列化", exception);
        }
    }
}
