package com.bosco.mine.telemetry.realtime;

import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
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
 * 校验实时遥测事件必须持久化发送并等待 RabbitMQ publisher confirm。
 */
class ConfirmedRealtimePublisherTest {

    @Test
    void 应发送持久化实时事件并等待Broker确认() throws Exception {
        RabbitOperations operations = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(operations.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        ConfirmedRealtimePublisher publisher = new ConfirmedRealtimePublisher(operations, 3000L);
        UUID deviceId = UUID.fromString("0198aef6-2e80-7000-8000-000000000101");
        RealtimeTelemetryEvent event = new RealtimeTelemetryEvent(
                UUID.fromString("0198aef6-2e80-7000-8000-000000000301"),
                deviceId,
                42L,
                Instant.parse("2026-07-15T02:00:00Z"),
                Instant.parse("2026-07-15T02:00:01Z"),
                Quality.GOOD,
                "boot-a",
                8L,
                Map.of("speed", 13.5D)
        );

        publisher.publish(event);

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(
                eq("mine.realtime.x"),
                eq("realtime.telemetry." + deviceId),
                message.capture()
        );
        verify(scoped).waitForConfirmsOrDie(3000L);
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getMessageId())
                .isEqualTo(event.eventId().toString());
        JsonNode json = JsonMapper.builder().findAndAddModules().build()
                .readTree(message.getValue().getBody());
        assertThat(json.get("sourceSequence").asLong()).isEqualTo(42L);
        assertThat(json.get("values").get("speed").asDouble()).isEqualTo(13.5D);
    }
}
