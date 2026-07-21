package com.bosco.mine.server.modules.command.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验命令事件按设备分区键持久化发送并等待 RabbitMQ 确认。
 */
class RabbitCommandOutboxPublisherTest {

    @Test
    void 应按设备分区键发送持久化消息并等待Broker确认() {
        RabbitOperations operations = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(operations.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        UUID eventId = UUID.fromString("019f64c4-a000-7000-8000-000000000001");
        UUID commandId = UUID.fromString("019f64c4-a000-7000-8000-000000000010");
        UUID deviceId = UUID.fromString("019f64c4-a000-7000-8000-000000000020");
        OutboxEvent event = new OutboxEvent(
                eventId, commandId, "command.dispatch.requested", 1,
                "{\"commandId\":\"" + commandId + "\","
                        + "\"deviceId\":\"" + deviceId + "\","
                        + "\"templateKey\":\"QUERY_STATUS\",\"templateVersion\":1,"
                        + "\"parameters\":{},\"riskLevel\":\"LOW\","
                        + "\"ttlSeconds\":300,\"timeoutSeconds\":30,"
                        + "\"expiresAt\":\"2026-07-15T07:05:00Z\"}",
                "{\"traceId\":\"trace-1\",\"partitionKey\":\"" + deviceId + "\"}",
                1, Instant.parse("2026-07-15T07:00:00Z")
        );

        new RabbitCommandOutboxPublisher(operations, 3000L).publish(event);

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(
                eq("mine.command.x"),
                eq("command.dispatch." + deviceId),
                message.capture()
        );
        verify(scoped).waitForConfirmsOrDie(3000L);
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getMessageProperties().getMessageId())
                .isEqualTo(eventId.toString());
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8))
                .contains("\"eventId\":\"" + eventId + "\"")
                .contains("\"producer\":\"mine-server\"")
                .contains("\"partitionKey\":\"" + deviceId + "\"")
                .contains("\"payload\":{")
                .contains("QUERY_STATUS");
    }
}
