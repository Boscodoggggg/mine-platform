package com.bosco.mine.telemetry.alarm.outbox;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
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
import java.util.UUID;

/**
 * 将白名单告警 Outbox 事件发布到业务交换机并等待 Broker 确认。
 */
public final class RabbitAlarmOutboxPublisher implements TelemetryOutboxRelay.Publisher {

    private final RabbitOperations rabbitOperations;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建告警事件确认发布器。
     *
     * @param rabbitOperations 平台 vhost RabbitMQ 操作器
     * @param confirmTimeoutMillis 等待发布确认的最大毫秒数
     */
    public RabbitAlarmOutboxPublisher(
            RabbitOperations rabbitOperations,
            long confirmTimeoutMillis
    ) {
        this.rabbitOperations = Objects.requireNonNull(
                rabbitOperations, "告警 RabbitMQ 操作器不能为空");
        if (confirmTimeoutMillis < 1L) {
            throw new IllegalArgumentException("告警发布确认超时必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /**
     * 重新校验已认领载荷并发送持久化统一信封。
     *
     * @param event 已认领告警 Outbox 事件
     */
    @Override
    public void publish(TelemetryOutboxRepository.ClaimedEvent event) {
        Objects.requireNonNull(event, "告警 Outbox 事件不能为空");
        ParsedAlarm parsed = parseAlarm(event);
        Map<String, Object> headers = readHeaders(event.headersJson());
        String traceId = requiredHeader(headers, "traceId");
        String partitionKey = requiredHeader(headers, "partitionKey");
        if (!parsed.occurrenceId().equals(event.aggregateId())) {
            throw new IllegalArgumentException("告警事件聚合标识与载荷周期不一致");
        }
        if (!parsed.deviceId().toString().equals(partitionKey)) {
            throw new IllegalArgumentException("告警事件设备分区键与载荷不一致");
        }
        if (!parsed.occurredAt().equals(event.occurredAt())) {
            throw new IllegalArgumentException("告警事件业务事实时间与载荷不一致");
        }
        MessageEnvelope<Object> envelope = new MessageEnvelope<>(
                event.eventId(),
                MessageType.of(event.eventType()),
                event.schemaVersion(),
                event.occurredAt(),
                Instant.now(),
                "mine-telemetry",
                traceId,
                event.aggregateId().toString(),
                partitionKey,
                "mine-local",
                parsed.payload()
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
            scoped.send(MineTopology.BUSINESS_EXCHANGE, event.eventType(), message);
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private ParsedAlarm parseAlarm(TelemetryOutboxRepository.ClaimedEvent event) {
        try {
            return switch (event.eventType()) {
                case MineTopology.ALARM_RAISED_ROUTING_KEY -> {
                    AlarmRaisedPayload payload = jsonMapper.readValue(
                            event.payloadJson(), AlarmRaisedPayload.class);
                    yield new ParsedAlarm(
                            payload.alarmOccurrenceId(), payload.deviceId(),
                            payload.raisedAt(), payload);
                }
                case MineTopology.ALARM_RECOVERED_ROUTING_KEY -> {
                    AlarmRecoveredPayload payload = jsonMapper.readValue(
                            event.payloadJson(), AlarmRecoveredPayload.class);
                    yield new ParsedAlarm(
                            payload.alarmOccurrenceId(), payload.deviceId(),
                            payload.recoveredAt(), payload);
                }
                default -> throw new IllegalArgumentException("告警 Outbox 事件类型不受支持");
            };
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("告警 Outbox 载荷 JSON 无法解析", exception);
        }
    }

    private Map<String, Object> readHeaders(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("告警 Outbox 消息头 JSON 无法解析", exception);
        }
    }

    private String requiredHeader(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) {
            throw new IllegalArgumentException("告警 Outbox 缺少有界消息头: " + name);
        }
        return text;
    }

    private byte[] writeEnvelope(MessageEnvelope<Object> envelope) {
        try {
            return jsonMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("告警消息信封无法序列化", exception);
        }
    }

    /** 已重新校验的告警载荷及其可靠路由身份。 */
    private record ParsedAlarm(
            UUID occurrenceId,
            UUID deviceId,
            Instant occurredAt,
            Object payload
    ) {
    }
}
