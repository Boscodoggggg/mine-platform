package com.bosco.mine.gateway.command;

import com.bosco.mine.common.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验命令超时使用单消息 TTL 持久化到专用等待路由。
 */
class RabbitCommandTimeoutSchedulerTest {

    @Test
    void 应持久化一次性超时事件并等待Broker确认() throws Exception {
        RabbitOperations operations = mock(RabbitOperations.class);
        RabbitOperations scoped = mock(RabbitOperations.class);
        when(operations.invoke(any())).thenAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            return callback.doInRabbit(scoped);
        });
        Instant now = Instant.parse("2026-07-15T08:00:00Z");
        RabbitCommandTimeoutScheduler scheduler = new RabbitCommandTimeoutScheduler(
                operations, IdGenerator.timeOrdered(), Clock.fixed(now, ZoneOffset.UTC), 5000L
        );
        UUID commandId = UUID.fromString("019f64e5-a000-7000-8000-000000000010");
        UUID deviceId = UUID.fromString("019f64e5-a000-7000-8000-000000000020");

        scheduler.schedule(
                commandId, deviceId, now.plusSeconds(30),
                "trace-command-1", "mine-site-001"
        );

        var message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(scoped).send(eq("mine.command.x"), eq("command.timeout.schedule"), message.capture());
        verify(scoped).waitForConfirmsOrDie(5000L);
        assertThat(message.getValue().getMessageProperties().getExpiration()).isEqualTo("30000");
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        JsonNode envelope = JsonMapper.builder().build().readTree(message.getValue().getBody());
        assertThat(envelope.get("eventType").asString()).isEqualTo("command.status.changed");
        assertThat(envelope.get("payload").get("status").asString()).isEqualTo("TIMEOUT");
        assertThat(envelope.get("payload").get("errorCode").asString())
                .isEqualTo("COMMAND_REPLY_TIMEOUT");
    }
}
