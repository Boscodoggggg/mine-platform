package com.bosco.mine.server.modules.command.status;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验命令状态消息只在数据库事务成功后确认。
 */
class CommandStatusConsumerTest {
    private static final UUID EVENT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000081");
    private static final UUID COMMAND_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000082");
    private static final UUID DEVICE_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000083");

    @Test
    void 事务成功后应确认状态消息() throws Exception {
        CommandStatusService service = mock(CommandStatusService.class);
        Channel channel = mock(Channel.class);
        CommandStatusConsumer consumer = new CommandStatusConsumer(service);

        consumer.onMessage(message(61L, validBody()), channel);

        verify(service).apply(any(UUID.class), any(CommandStatusPayload.class));
        verify(channel).basicAck(61L, false);
    }

    @Test
    void 数据库暂时失败时应重新投递() throws Exception {
        CommandStatusService service = mock(CommandStatusService.class);
        Channel channel = mock(Channel.class);
        doThrow(new IllegalStateException("数据库暂时不可用"))
                .when(service).apply(any(UUID.class), any(CommandStatusPayload.class));
        CommandStatusConsumer consumer = new CommandStatusConsumer(service);

        consumer.onMessage(message(62L, validBody()), channel);

        verify(channel).basicNack(62L, false, true);
    }

    @Test
    void 非法消息应拒绝并进入死信队列() throws Exception {
        CommandStatusService service = mock(CommandStatusService.class);
        Channel channel = mock(Channel.class);
        CommandStatusConsumer consumer = new CommandStatusConsumer(service);

        consumer.onMessage(message(63L, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), channel);

        verify(channel).basicReject(63L, false);
    }

    private byte[] validBody() throws Exception {
        Instant occurredAt = Instant.parse("2026-07-15T08:00:03Z");
        MessageEnvelope<CommandStatusPayload> envelope = new MessageEnvelope<>(
                EVENT_ID, MessageType.of("command.status.changed"), 1,
                occurredAt, occurredAt, "mine-gateway", "trace-command-status",
                COMMAND_ID.toString(), DEVICE_ID.toString(), "mine-site-001",
                new CommandStatusPayload(
                        COMMAND_ID, DEVICE_ID, "SUCCEEDED", occurredAt,
                        occurredAt.minusSeconds(1), Map.of("online", true), null
                )
        );
        return JsonMapper.builder().findAndAddModules().build().writeValueAsBytes(envelope);
    }

    private Message message(long deliveryTag, byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey("command.status.succeeded");
        return new Message(body, properties);
    }
}
