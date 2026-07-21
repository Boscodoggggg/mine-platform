package com.bosco.mine.server.modules.command.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验定位运行事件只发送快照元数据并等待 RabbitMQ 确认。
 */
class RabbitLocationSnapshotOutboxPublisherTest {

    @Test
    void 应按矿区分区发送持久化轻量事件() {
        RabbitOperations operations = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(operations.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        UUID eventId = UUID.fromString("019f6d00-0000-7000-8000-000000000101");
        UUID snapshotId = UUID.fromString("019f6d00-0000-7000-8000-000000000110");
        UUID siteId = UUID.fromString("019f6d00-0000-7000-8000-000000000120");
        OutboxEvent event = new OutboxEvent(
                eventId, snapshotId, "location.runtime.changed", 1,
                "{\"snapshotId\":\"" + snapshotId + "\","
                        + "\"revision\":7,\"contentHash\":\"sha256:"
                        + "a".repeat(64) + "\"}",
                "{\"traceId\":\"trace-location-1\",\"partitionKey\":\""
                        + siteId + "\"}",
                1, Instant.parse("2026-07-18T01:00:00Z"));

        new RabbitLocationSnapshotOutboxPublisher(operations, 3000L).publish(event);

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(
                eq("mine.location-config.x"),
                eq("location.runtime." + siteId),
                message.capture());
        verify(scoped).waitForConfirmsOrDie(3000L);
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8))
                .contains("\"eventType\":\"location.runtime.changed\"")
                .contains("\"revision\":7")
                .contains("\"contentHash\":\"sha256:")
                .doesNotContain("items", "contentJson", "phone", "certificate");
    }
}
