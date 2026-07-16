package com.bosco.mine.gateway.configuration;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.configuration.ConfigReleasePayload;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验配置事件的手工确认、瞬时重试和不可重试死信规则。
 */
class ConfigurationReleaseConsumerTest {

    @Test
    void 完整快照匹配并激活后才应确认配置事件() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshot snapshot = snapshot(payload);
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        ProtocolSnapshotRegistry registry = mock(ProtocolSnapshotRegistry.class);
        when(client.fetch(payload.releaseId())).thenReturn(snapshot);
        when(registry.activate(snapshot)).thenReturn(ProtocolSnapshotRegistry.ActivationResult.ACTIVATED);
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, registry);
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(41), channel);

        verify(registry).activate(snapshot);
        verify(client).reportLoaded(payload.releaseId(), payload.contentHash());
        verify(channel).basicAck(41, false);
        verify(channel, never()).basicNack(41, false, true);
    }

    @Test
    void 节点状态暂时无法回报时应重新投递且不能提前确认() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshot snapshot = snapshot(payload);
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        ProtocolSnapshotRegistry registry = mock(ProtocolSnapshotRegistry.class);
        when(client.fetch(payload.releaseId())).thenReturn(snapshot);
        when(registry.activate(snapshot)).thenReturn(ProtocolSnapshotRegistry.ActivationResult.ACTIVATED);
        org.mockito.Mockito.doThrow(new ConfigurationFetchException("状态服务暂时不可用"))
                .when(client).reportLoaded(payload.releaseId(), payload.contentHash());
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, registry);
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(45), channel);

        verify(registry).activate(snapshot);
        verify(channel).basicNack(45, false, true);
        verify(channel, never()).basicAck(45, false);
    }

    @Test
    void 拉取瞬时失败应重新投递而不是丢弃() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        when(client.fetch(payload.releaseId())).thenThrow(
                new ConfigurationFetchException("管理服务暂时不可用"));
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, mock(ProtocolSnapshotRegistry.class));
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(42), channel);

        verify(channel).basicNack(42, false, true);
    }

    @Test
    void 事件与快照不一致或快照损坏应进入死信且不覆盖旧配置() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        ConfigurationSnapshot mismatched = new ConfigurationSnapshot(
                payload.releaseId(), payload.productId(), UUID.randomUUID(), payload.releaseSequence(),
                payload.contentHash(), "{}"
        );
        when(client.fetch(payload.releaseId())).thenReturn(mismatched);
        ProtocolSnapshotRegistry registry = mock(ProtocolSnapshotRegistry.class);
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, registry);
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(43), channel);

        verify(registry, never()).activate(mismatched);
        verify(channel).basicReject(43, false);
    }

    @Test
    void 非法消息应直接进入死信() throws Exception {
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> {
                    throw new InvalidConfigurationMessageException("事件类型错误");
                },
                mock(ConfigurationSnapshotClient.class),
                mock(ProtocolSnapshotRegistry.class));
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(44), channel);

        verify(channel).basicReject(44, false);
    }

    private ConfigReleasePayload payload() {
        return new ConfigReleasePayload(
                UUID.fromString("019f6a12-a000-7000-8000-000000000010"),
                UUID.fromString("019f6a12-a000-7000-8000-000000000020"),
                UUID.fromString("019f6a12-a000-7000-8000-000000000030"),
                7,
                "sha256:" + "a".repeat(64)
        );
    }

    private ConfigurationSnapshot snapshot(ConfigReleasePayload payload) {
        return new ConfigurationSnapshot(
                payload.releaseId(), payload.productId(), payload.packageId(),
                payload.releaseSequence(), payload.contentHash(), "{}"
        );
    }

    private MessageEnvelope<ConfigReleasePayload> envelope(ConfigReleasePayload payload) {
        return new MessageEnvelope<>(
                UUID.fromString("019f6a12-a000-7000-8000-000000000001"),
                MessageType.of("config.release.created"), 1,
                Instant.parse("2026-07-16T08:00:00Z"),
                Instant.parse("2026-07-16T08:00:01Z"),
                "mine-server", "trace-config", payload.releaseId().toString(),
                payload.productId().toString(), "mine-local", payload
        );
    }

    private Message message(long deliveryTag) {
        return MessageBuilder.withBody("{}".getBytes())
                .setDeliveryTag(deliveryTag)
                .build();
    }
}
