package com.bosco.mine.telemetry.location;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.messaging.topology.MineTopology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 重新校验持久位置载荷，按矿区路由并等待 RabbitMQ 相关发布确认。
 */
public final class ConfirmedRealtimeLocationPublisher
        implements LocationRealtimeOutboxRelay.Publisher {

    private final RabbitOperations rabbitOperations;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建位置实时确认发布器。
     *
     * @param rabbitOperations 平台 vhost RabbitMQ 操作器
     * @param confirmTimeoutMillis 等待 Broker 确认的最大毫秒数
     */
    public ConfirmedRealtimeLocationPublisher(
            RabbitOperations rabbitOperations,
            long confirmTimeoutMillis
    ) {
        this.rabbitOperations = Objects.requireNonNull(
                rabbitOperations, "位置实时 RabbitMQ 操作器不能为空");
        if (confirmTimeoutMillis < 1L) {
            throw new IllegalArgumentException("位置实时发布确认超时必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /** {@inheritDoc} */
    @Override
    public void publish(LocationRealtimeOutboxRelay.ClaimedEvent claimed) {
        Objects.requireNonNull(claimed, "位置实时 Outbox 事件不能为空");
        RealtimeLocationEvent payload = readPayload(claimed.payloadJson());
        Map<String, Object> headers = readHeaders(claimed.headersJson());
        if (!payload.eventId().equals(claimed.eventId())
                || payload.sourceSequence() != claimed.ingestSequence()
                || !payload.occurredAt().equals(claimed.occurredAt())
                || !payload.siteId().toString().equals(requiredHeader(headers, "partitionKey"))
                || !payload.tagId().toString().equals(requiredHeader(headers, "tagId"))
                || !hash(canonicalJson(payload)).equals(claimed.payloadHash())) {
            throw new IllegalArgumentException("位置实时 Outbox 元数据、载荷或摘要不一致");
        }
        MessageEnvelope<RealtimeLocationEvent> envelope = new MessageEnvelope<>(
                claimed.eventId(), MessageType.of(claimed.eventType()),
                claimed.schemaVersion(), claimed.occurredAt(), Instant.now(),
                "mine-telemetry", claimed.eventId().toString(),
                payload.tagId().toString(), payload.siteId().toString(),
                "mine-local", payload);
        Message message = MessageBuilder.withBody(write(envelope))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(claimed.eventId().toString())
                .setHeader("eventType", claimed.eventType())
                .setHeader("schemaVersion", claimed.schemaVersion())
                .setHeader("siteId", payload.siteId().toString())
                .build();
        CorrelationData correlation = new CorrelationData(claimed.eventId().toString());
        rabbitOperations.send(
                MineTopology.REALTIME_EXCHANGE,
                "realtime.location." + payload.siteId(), message, correlation);
        CorrelationData.Confirm confirm = awaitConfirm(correlation);
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("位置实时消息不可路由: "
                    + correlation.getReturned().getReplyText());
        }
        if (!confirm.ack()) {
            throw new IllegalStateException("Broker 拒绝位置实时消息: " + confirm.reason());
        }
    }

    private CorrelationData.Confirm awaitConfirm(CorrelationData correlation) {
        try {
            return correlation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待位置实时发布确认时线程被中断", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("等待位置实时发布确认超时", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("等待位置实时发布确认失败", exception.getCause());
        }
    }

    private RealtimeLocationEvent readPayload(String json) {
        try {
            return jsonMapper.readValue(json, RealtimeLocationEvent.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("位置实时 Outbox 载荷无法解析", exception);
        }
    }

    private Map<String, Object> readHeaders(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("位置实时 Outbox 消息头无法解析", exception);
        }
    }

    private String requiredHeader(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
            throw new IllegalArgumentException("位置实时 Outbox 缺少有界消息头: " + name);
        }
        return text;
    }

    private byte[] write(MessageEnvelope<RealtimeLocationEvent> envelope) {
        try {
            return jsonMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("位置实时消息信封无法序列化", exception);
        }
    }

    private String canonicalJson(Object value) {
        try {
            Object tree = jsonMapper.treeToValue(jsonMapper.valueToTree(value), Object.class);
            return jsonMapper.writeValueAsString(canonicalize(tree));
        } catch (JacksonException exception) {
            throw new IllegalStateException("位置实时载荷无法规范化", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.TreeMap<>();
            map.forEach((key, item) -> result.put(
                    String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }
}
