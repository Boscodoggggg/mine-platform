package com.bosco.mine.gateway.command;

import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验设备业务 ACK 只有在状态事件确认后才确认原始上行消息。
 */
class CommandAckConsumerTest {

    @Test
    void 业务ACK应发布终态和设备收到时间() throws Exception {
        CommandAckConsumer.Decoder decoder = mock(CommandAckConsumer.Decoder.class);
        CommandStatusEventPublisher publisher = mock(CommandStatusEventPublisher.class);
        Channel channel = mock(Channel.class);
        CommandAckConsumer.DecodedAck ack = ack();
        when(decoder.decode(any(), any())).thenReturn(ack);
        CommandAckConsumer consumer = new CommandAckConsumer(decoder, publisher);

        consumer.onMessage(message(51L), channel);

        var status = org.mockito.ArgumentCaptor.forClass(CommandStatusPayload.class);
        verify(publisher).publish(status.capture(), org.mockito.ArgumentMatchers.eq("trace-command-1"),
                org.mockito.ArgumentMatchers.eq("mine-site-001"));
        verify(channel).basicAck(51L, false);
        assertThat(status.getValue().status()).isEqualTo("SUCCEEDED");
        assertThat(status.getValue().deviceReceivedAt()).isEqualTo(ack.occurredAt());
        assertThat(status.getValue().reply()).containsEntry("online", true);
    }

    @Test
    void 状态事件未确认时应重投设备ACK() throws Exception {
        CommandAckConsumer.Decoder decoder = mock(CommandAckConsumer.Decoder.class);
        CommandStatusEventPublisher publisher = mock(CommandStatusEventPublisher.class);
        Channel channel = mock(Channel.class);
        CommandAckConsumer.DecodedAck ack = ack();
        when(decoder.decode(any(), any())).thenReturn(ack);
        doThrow(new IllegalStateException("状态事件确认超时"))
                .when(publisher).publish(any(), any(), any());
        CommandAckConsumer consumer = new CommandAckConsumer(decoder, publisher);

        consumer.onMessage(message(52L), channel);

        verify(channel).basicNack(52L, false, true);
    }

    @Test
    void 非法ACK应拒绝并进入设备死信() throws Exception {
        CommandAckConsumer.Decoder decoder = mock(CommandAckConsumer.Decoder.class);
        CommandStatusEventPublisher publisher = mock(CommandStatusEventPublisher.class);
        Channel channel = mock(Channel.class);
        when(decoder.decode(any(), any())).thenThrow(new InvalidCommandMessageException("身份不匹配"));
        CommandAckConsumer consumer = new CommandAckConsumer(decoder, publisher);

        consumer.onMessage(message(53L), channel);

        verify(channel).basicReject(53L, false);
    }

    private CommandAckConsumer.DecodedAck ack() {
        return new CommandAckConsumer.DecodedAck(
                UUID.fromString("019f64e5-a000-7000-8000-000000000010"),
                UUID.fromString("019f64e5-a000-7000-8000-000000000020"),
                "SUCCEEDED", Instant.parse("2026-07-15T08:00:03Z"),
                Map.of("online", true), null, "trace-command-1", "mine-site-001"
        );
    }

    private Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey("mine.v1.haul-truck.device-credential-001.up.command-ack");
        return new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }
}
