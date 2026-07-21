package com.bosco.mine.gateway.command;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验命令下行只有在设备侧 Broker 接收且状态事件确认后才确认平台消息。
 */
class CommandDispatchConsumerTest {

    private static final Instant NOW = Instant.parse("2026-07-15T08:00:00Z");

    @Test
    void 设备侧Broker确认后应发布SENT并确认原始消息() throws Exception {
        CommandDispatchConsumer.Decoder decoder = mock(CommandDispatchConsumer.Decoder.class);
        CommandDispatchConsumer.DownlinkPublisher downlink = mock(CommandDispatchConsumer.DownlinkPublisher.class);
        CommandStatusEventPublisher statusPublisher = mock(CommandStatusEventPublisher.class);
        CommandTimeoutScheduler timeoutScheduler = mock(CommandTimeoutScheduler.class);
        Channel channel = mock(Channel.class);
        MessageEnvelope<CommandDispatchPayload> envelope = envelope();
        when(decoder.decode(any())).thenReturn(envelope);
        CommandDispatchConsumer consumer = new CommandDispatchConsumer(
                decoder, downlink, statusPublisher, timeoutScheduler, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        consumer.onMessage(message(41L), channel);

        var status = org.mockito.ArgumentCaptor.forClass(CommandStatusPayload.class);
        InOrder order = inOrder(downlink, statusPublisher, timeoutScheduler, channel);
        order.verify(downlink).publish(envelope);
        order.verify(statusPublisher).publish(status.capture(), org.mockito.ArgumentMatchers.eq("trace-command-1"),
                org.mockito.ArgumentMatchers.eq("mine-site-001"));
        order.verify(timeoutScheduler).schedule(
                org.mockito.ArgumentMatchers.eq(envelope.payload().commandId()),
                org.mockito.ArgumentMatchers.eq(envelope.payload().deviceId()),
                org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(30)),
                org.mockito.ArgumentMatchers.eq("trace-command-1"),
                org.mockito.ArgumentMatchers.eq("mine-site-001")
        );
        order.verify(channel).basicAck(41L, false);
        assertThat(status.getValue().status()).isEqualTo("SENT");
        assertThat(status.getValue().commandId()).isEqualTo(envelope.payload().commandId());
        assertThat(status.getValue().deviceReceivedAt()).isNull();
    }

    @Test
    void 无回复原始命令在Broker确认后应发布PUBLISHED且不创建超时任务() throws Exception {
        CommandDispatchConsumer.Decoder decoder = mock(CommandDispatchConsumer.Decoder.class);
        CommandDispatchConsumer.DownlinkPublisher downlink = mock(CommandDispatchConsumer.DownlinkPublisher.class);
        CommandStatusEventPublisher statusPublisher = mock(CommandStatusEventPublisher.class);
        CommandTimeoutScheduler timeoutScheduler = mock(CommandTimeoutScheduler.class);
        Channel channel = mock(Channel.class);
        MessageEnvelope<CommandDispatchPayload> envelope = rawEnvelope();
        when(decoder.decode(any())).thenReturn(envelope);
        CommandDispatchConsumer consumer = new CommandDispatchConsumer(
                decoder, downlink, statusPublisher, timeoutScheduler, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        consumer.onMessage(message(44L), channel);

        var status = org.mockito.ArgumentCaptor.forClass(CommandStatusPayload.class);
        verify(statusPublisher).publish(status.capture(),
                org.mockito.ArgumentMatchers.eq("trace-command-1"),
                org.mockito.ArgumentMatchers.eq("mine-site-001"));
        verify(timeoutScheduler, never()).schedule(any(), any(), any(), any(), any());
        verify(channel).basicAck(44L, false);
        assertThat(status.getValue().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void 设备侧发布未确认时应重投且不得发布SENT() throws Exception {
        CommandDispatchConsumer.Decoder decoder = mock(CommandDispatchConsumer.Decoder.class);
        CommandDispatchConsumer.DownlinkPublisher downlink = mock(CommandDispatchConsumer.DownlinkPublisher.class);
        CommandStatusEventPublisher statusPublisher = mock(CommandStatusEventPublisher.class);
        CommandTimeoutScheduler timeoutScheduler = mock(CommandTimeoutScheduler.class);
        Channel channel = mock(Channel.class);
        MessageEnvelope<CommandDispatchPayload> envelope = envelope();
        when(decoder.decode(any())).thenReturn(envelope);
        doThrow(new IllegalStateException("设备侧 publisher confirm 超时")).when(downlink).publish(envelope);
        CommandDispatchConsumer consumer = new CommandDispatchConsumer(
                decoder, downlink, statusPublisher, timeoutScheduler, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        consumer.onMessage(message(42L), channel);

        verify(statusPublisher, never()).publish(any(), any(), any());
        verify(channel).basicNack(42L, false, true);
    }

    @Test
    void 非法命令消息应拒绝并进入死信() throws Exception {
        CommandDispatchConsumer.Decoder decoder = mock(CommandDispatchConsumer.Decoder.class);
        CommandDispatchConsumer.DownlinkPublisher downlink = mock(CommandDispatchConsumer.DownlinkPublisher.class);
        CommandStatusEventPublisher statusPublisher = mock(CommandStatusEventPublisher.class);
        CommandTimeoutScheduler timeoutScheduler = mock(CommandTimeoutScheduler.class);
        Channel channel = mock(Channel.class);
        when(decoder.decode(any())).thenThrow(new InvalidCommandMessageException("事件类型错误"));
        CommandDispatchConsumer consumer = new CommandDispatchConsumer(
                decoder, downlink, statusPublisher, timeoutScheduler, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        consumer.onMessage(message(43L), channel);

        verify(downlink, never()).publish(any());
        verify(channel).basicReject(43L, false);
    }

    @Test
    void 已过期命令应回报EXPIRED并确认原始消息() throws Exception {
        CommandDispatchConsumer.Decoder decoder = mock(CommandDispatchConsumer.Decoder.class);
        CommandDispatchConsumer.DownlinkPublisher downlink = mock(CommandDispatchConsumer.DownlinkPublisher.class);
        CommandStatusEventPublisher statusPublisher = mock(CommandStatusEventPublisher.class);
        CommandTimeoutScheduler timeoutScheduler = mock(CommandTimeoutScheduler.class);
        Channel channel = mock(Channel.class);
        MessageEnvelope<CommandDispatchPayload> envelope = expiredEnvelope();
        when(decoder.decode(any())).thenReturn(envelope);
        CommandDispatchConsumer consumer = new CommandDispatchConsumer(
                decoder, downlink, statusPublisher, timeoutScheduler, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        consumer.onMessage(message(45L), channel);

        var status = org.mockito.ArgumentCaptor.forClass(CommandStatusPayload.class);
        verify(downlink, never()).publish(any());
        verify(statusPublisher).publish(status.capture(),
                org.mockito.ArgumentMatchers.eq("trace-command-1"),
                org.mockito.ArgumentMatchers.eq("mine-site-001"));
        verify(timeoutScheduler, never()).schedule(any(), any(), any(), any(), any());
        verify(channel).basicAck(45L, false);
        assertThat(status.getValue().status()).isEqualTo("EXPIRED");
        assertThat(status.getValue().occurredAt()).isEqualTo(NOW);
        assertThat(status.getValue().errorCode()).isEqualTo("COMMAND_TTL_EXPIRED");
    }

    private Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey("command.dispatch.019f64e5-a000-7000-8000-000000000020");
        return new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }

    private MessageEnvelope<CommandDispatchPayload> envelope() {
        UUID commandId = UUID.fromString("019f64e5-a000-7000-8000-000000000010");
        UUID deviceId = UUID.fromString("019f64e5-a000-7000-8000-000000000020");
        return new MessageEnvelope<>(
                UUID.fromString("019f64e5-a000-7000-8000-000000000001"),
                MessageType.of("command.dispatch.requested"), 1,
                NOW.minusSeconds(1), NOW.minusSeconds(1), "mine-server",
                "trace-command-1", commandId.toString(), deviceId.toString(), "mine-site-001",
                new CommandDispatchPayload(
                        commandId, deviceId, "QUERY_STATUS", 1, Map.of(), "LOW",
                        300, 30, NOW.plusSeconds(300)
                )
        );
    }

    private MessageEnvelope<CommandDispatchPayload> rawEnvelope() {
        UUID commandId = UUID.fromString("019f64e5-a000-7000-8000-000000000010");
        UUID deviceId = UUID.fromString("019f64e5-a000-7000-8000-000000000020");
        return new MessageEnvelope<>(
                UUID.fromString("019f64e5-a000-7000-8000-000000000001"),
                MessageType.of("command.dispatch.requested"), 1,
                NOW.minusSeconds(1), NOW.minusSeconds(1), "mine-server",
                "trace-command-1", commandId.toString(), deviceId.toString(), "mine-site-001",
                new CommandDispatchPayload(
                        commandId, deviceId, "RAW_HEX_AD_HOC", 1, Map.of(), "LOW",
                        300, 30, NOW.plusSeconds(300), "RAW_HEX", "qrsB", false
                )
        );
    }

    private MessageEnvelope<CommandDispatchPayload> expiredEnvelope() {
        UUID commandId = UUID.fromString("019f64e5-a000-7000-8000-000000000010");
        UUID deviceId = UUID.fromString("019f64e5-a000-7000-8000-000000000020");
        return new MessageEnvelope<>(
                UUID.fromString("019f64e5-a000-7000-8000-000000000001"),
                MessageType.of("command.dispatch.requested"), 1,
                NOW.minusSeconds(301), NOW.minusSeconds(300), "mine-server",
                "trace-command-1", commandId.toString(), deviceId.toString(), "mine-site-001",
                new CommandDispatchPayload(
                        commandId, deviceId, "QUERY_STATUS", 1, Map.of(), "LOW",
                        300, 30, NOW.minusSeconds(1)
                )
        );
    }
}
