package com.bosco.mine.gateway.mqtt;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验内部遥测发布必须在同一 RabbitMQ 通道等待 publisher confirm。
 */
class ConfirmedTelemetryPublisherTest {

    @Test
    void 应发送持久化JSON并等待Broker确认() throws Exception {
        RabbitOperations template = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(template.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        ConfirmedTelemetryPublisher publisher = new ConfirmedTelemetryPublisher(template, 5000);
        MessageEnvelope<UnifiedDeviceMessage> envelope = envelope();

        publisher.publish(envelope);

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(
                eq("mine.telemetry.x"),
                eq("telemetry.normalized.haul-truck.simulator-001"),
                messageCaptor.capture()
        );
        verify(scoped).waitForConfirmsOrDie(5000);
        Message message = messageCaptor.getValue();
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        JsonNode json = JsonMapper.builder().build().readTree(message.getBody());
        assertThat(json.get("eventType").asString()).isEqualTo("telemetry.normalized");
        assertThat(json.get("payload").get("deviceId").asString()).isEqualTo("simulator-001");
    }

    private MessageEnvelope<UnifiedDeviceMessage> envelope() {
        UUID messageId = UUID.fromString("019b30ea-5c00-7000-8000-000000000001");
        Instant occurredAt = Instant.parse("2026-07-15T01:59:58Z");
        Instant publishedAt = Instant.parse("2026-07-15T02:00:00Z");
        UnifiedDeviceMessage payload = new UnifiedDeviceMessage(
                messageId,
                UnifiedDeviceMessage.MessageKind.TELEMETRY,
                "haul-truck",
                "simulator-001",
                "mqtt-json-v1",
                "boot-001",
                7L,
                occurredAt,
                publishedAt,
                false,
                new TelemetryPayload(Map.of("engine.rpm", 1280)),
                UnifiedDeviceMessage.Quality.GOOD,
                null
        );
        return new MessageEnvelope<>(
                messageId,
                MessageType.of("telemetry.normalized"),
                1,
                occurredAt,
                publishedAt,
                "mine-gateway",
                messageId.toString(),
                null,
                "simulator-001",
                "mine-site-001",
                payload
        );
    }
}
