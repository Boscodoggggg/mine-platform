package com.bosco.mine.server.modules.command.outbox;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 将命令 Outbox JSON 发布到持久化命令交换机并等待 Broker confirm。
 */
public final class RabbitCommandOutboxPublisher implements OutboxEventPublisher {
    private final RabbitOperations rabbitOperations;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建 RabbitMQ 命令事件发布器。
     *
     * @param rabbitOperations RabbitMQ 操作器
     * @param confirmTimeoutMillis 发布确认超时毫秒数
     */
    public RabbitCommandOutboxPublisher(
            RabbitOperations rabbitOperations,
            long confirmTimeoutMillis
    ) {
        this.rabbitOperations = Objects.requireNonNull(rabbitOperations, "RabbitMQ 操作器不能为空");
        if (confirmTimeoutMillis < 1L) {
            throw new IllegalArgumentException("发布确认超时必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /**
     * 发布持久化命令事件并同步等待 Broker 确认。
     *
     * @param event 已认领事件
     */
    @Override
    public void publish(OutboxEvent event) {
        Objects.requireNonNull(event, "Outbox 事件不能为空");
        Map<String, Object> headers = readHeaders(event.headersJson());
        String partitionKey = requiredHeader(headers, "partitionKey");
        String traceId = requiredHeader(headers, "traceId");
        CommandDispatchPayload payload = readPayload(event.payloadJson());
        MessageEnvelope<CommandDispatchPayload> envelope = new MessageEnvelope<>(
                event.eventId(), MessageType.of(event.eventType()), event.schemaVersion(),
                event.occurredAt(), Instant.now(), "mine-server", traceId,
                event.aggregateId().toString(), partitionKey, "mine-local", payload
        );
        Message message = MessageBuilder
                .withBody(writeEnvelope(envelope))
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
                    MineTopology.COMMAND_EXCHANGE,
                    "command.dispatch." + partitionKey,
                    message
            );
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private Map<String, Object> readHeaders(String headersJson) {
        try {
            return jsonMapper.readValue(headersJson, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("命令事件头 JSON 无法解析", exception);
        }
    }

    private CommandDispatchPayload readPayload(String payloadJson) {
        try {
            return jsonMapper.readValue(payloadJson, CommandDispatchPayload.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("命令事件载荷 JSON 无法解析", exception);
        }
    }

    private byte[] writeEnvelope(MessageEnvelope<CommandDispatchPayload> envelope) {
        try {
            return jsonMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("命令消息信封无法序列化", exception);
        }
    }

    private String requiredHeader(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("命令事件缺少消息头: " + name);
        }
        return text;
    }
}
