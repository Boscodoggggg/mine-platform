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
 * 校验策略运行事件只发送快照定位信息并等待 RabbitMQ 确认。
 */
class RabbitStrategyOutboxPublisherTest {

    @Test
    void 应按产品和策略类型发送持久化轻量事件() {
        RabbitOperations operations = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(operations.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        UUID eventId = UUID.fromString("019f6d00-0000-7000-8000-000000000001");
        UUID snapshotId = UUID.fromString("019f6d00-0000-7000-8000-000000000010");
        UUID productId = UUID.fromString("019f6d00-0000-7000-8000-000000000020");
        OutboxEvent event = new OutboxEvent(
                eventId, snapshotId, "strategy.runtime.changed", 1,
                "{\"snapshotId\":\"" + snapshotId + "\","
                        + "\"productId\":\"" + productId + "\","
                        + "\"strategyType\":\"DATA_QUALITY\","
                        + "\"runtimeRevision\":7,\"itemCount\":120,"
                        + "\"contentHash\":\"sha256:" + "a".repeat(64) + "\"}",
                "{\"traceId\":\"trace-strategy-1\",\"partitionKey\":\""
                        + productId + "\"}",
                1, Instant.parse("2026-07-17T01:00:00Z")
        );

        new RabbitStrategyOutboxPublisher(operations, 3000L).publish(event);

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(
                eq("mine.strategy.x"),
                eq("strategy.runtime.data_quality." + productId),
                message.capture()
        );
        verify(scoped).waitForConfirmsOrDie(3000L);
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8))
                .contains("\"eventType\":\"strategy.runtime.changed\"")
                .contains("\"runtimeRevision\":7")
                .contains("\"itemCount\":120")
                .doesNotContain("values", "items");
    }
}
