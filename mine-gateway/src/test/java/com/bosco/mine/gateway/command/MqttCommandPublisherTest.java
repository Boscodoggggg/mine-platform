package com.bosco.mine.gateway.command;

import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验平台命令按可信设备身份编码到专属 MQTT 下行主题。
 */
class MqttCommandPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00Z");

    @Test
    void 应发布持久化命令到设备专属主题并等待确认() throws Exception {
        RabbitOperations operations = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(operations.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        DeviceSessionRegistry registry = registry();
        MessageEnvelope<CommandDispatchPayload> envelope = envelope(NOW.plusSeconds(120));
        MqttCommandPublisher publisher = new MqttCommandPublisher(
                operations, registry, Clock.fixed(NOW, ZoneOffset.UTC), 5000L
        );

        publisher.publish(envelope);

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(
                eq("amq.topic"),
                eq("mine.v1.haul-truck.device-credential-001.down.command"),
                message.capture()
        );
        verify(scoped).waitForConfirmsOrDie(5000L);
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getExpiration()).isEqualTo("120000");
        JsonNode body = JsonMapper.builder().build().readTree(message.getValue().getBody());
        assertThat(body.get("commandId").asString()).isEqualTo(envelope.payload().commandId().toString());
        assertThat(body.get("command").asString()).isEqualTo("QUERY_STATUS");
        assertThat(body.get("traceId").asString()).isEqualTo("trace-command-1");
    }

    @Test
    void 未登记设备或已过期命令不得下发() {
        RabbitOperations operations = mock(RabbitOperations.class);
        DeviceSessionRegistry empty = new DeviceSessionRegistry();
        MqttCommandPublisher missingIdentity = new MqttCommandPublisher(
                operations, empty, Clock.fixed(NOW, ZoneOffset.UTC), 5000L
        );
        MqttCommandPublisher expired = new MqttCommandPublisher(
                operations, registry(), Clock.fixed(NOW, ZoneOffset.UTC), 5000L
        );

        assertThatThrownBy(() -> missingIdentity.publish(envelope(NOW.plusSeconds(1))))
                .isInstanceOf(InvalidCommandMessageException.class)
                .hasMessageContaining("设备身份");
        assertThatThrownBy(() -> expired.publish(envelope(NOW)))
                .isInstanceOf(InvalidCommandMessageException.class)
                .hasMessageContaining("过期");
    }

    private DeviceSessionRegistry registry() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.replace(List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck", "device-credential-001",
                "019f64e5-a000-7000-8000-000000000020", "truck-001",
                "mine-site-001", "mqtt-json-v1"
        )));
        return registry;
    }

    private MessageEnvelope<CommandDispatchPayload> envelope(Instant expiresAt) {
        UUID commandId = UUID.fromString("019f64e5-a000-7000-8000-000000000010");
        UUID deviceId = UUID.fromString("019f64e5-a000-7000-8000-000000000020");
        return new MessageEnvelope<>(
                UUID.fromString("019f64e5-a000-7000-8000-000000000001"),
                MessageType.of("command.dispatch.requested"), 1,
                NOW.minusSeconds(1), NOW.minusSeconds(1), "mine-server",
                "trace-command-1", commandId.toString(), deviceId.toString(), "mine-site-001",
                new CommandDispatchPayload(
                        commandId, deviceId, "QUERY_STATUS", 1, Map.of(), "LOW",
                        300, 30, expiresAt
                )
        );
    }
}
