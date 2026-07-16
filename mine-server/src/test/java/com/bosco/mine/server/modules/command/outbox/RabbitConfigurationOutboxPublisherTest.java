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
 * 校验配置发布事件只携带发布定位信息并等待 RabbitMQ 确认。
 */
class RabbitConfigurationOutboxPublisherTest {

    @Test
    void 应按产品分区发送持久化配置发布事件() {
        RabbitOperations operations = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(operations.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        UUID eventId = UUID.fromString("019f6a11-a000-7000-8000-000000000001");
        UUID releaseId = UUID.fromString("019f6a11-a000-7000-8000-000000000010");
        UUID packageId = UUID.fromString("019f6a11-a000-7000-8000-000000000020");
        UUID productId = UUID.fromString("019f6a11-a000-7000-8000-000000000030");
        OutboxEvent event = new OutboxEvent(
                eventId, releaseId, "config.release.created", 1,
                "{\"releaseId\":\"" + releaseId + "\","
                        + "\"packageId\":\"" + packageId + "\","
                        + "\"productId\":\"" + productId + "\","
                        + "\"releaseSequence\":7,"
                        + "\"contentHash\":\"sha256:" + "a".repeat(64) + "\"}",
                "{\"traceId\":\"trace-config-1\",\"partitionKey\":\"" + productId + "\"}",
                1, Instant.parse("2026-07-16T08:00:00Z")
        );

        new RabbitConfigurationOutboxPublisher(operations, 3000L).publish(event);

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(
                eq("mine.config.x"),
                eq("config.release." + productId),
                message.capture()
        );
        verify(scoped).waitForConfirmsOrDie(3000L);
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8))
                .contains("\"eventType\":\"config.release.created\"")
                .contains("\"releaseSequence\":7")
                .contains("\"contentHash\":\"sha256:")
                .doesNotContain("snapshot");
    }
}
