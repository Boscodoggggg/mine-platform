package com.bosco.mine.gateway.configuration;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.configuration.ConfigReleasePayload;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
        ConfigurationSnapshotStore store = mock(ConfigurationSnapshotStore.class);
        when(client.fetch(payload.releaseId())).thenReturn(snapshot);
        when(registry.activate(snapshot)).thenReturn(ProtocolSnapshotRegistry.ActivationResult.ACTIVATED);
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, registry, store);
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(41), channel);

        verify(registry).activate(snapshot);
        verify(store).persist(snapshot);
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
        ConfigurationSnapshotStore store = mock(ConfigurationSnapshotStore.class);
        when(client.fetch(payload.releaseId())).thenReturn(snapshot);
        when(registry.activate(snapshot)).thenReturn(ProtocolSnapshotRegistry.ActivationResult.ACTIVATED);
        org.mockito.Mockito.doThrow(new ConfigurationFetchException("状态服务暂时不可用"))
                .when(client).reportLoaded(payload.releaseId(), payload.contentHash());
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, registry, store);
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(45), channel);

        verify(registry).activate(snapshot);
        verify(channel).basicNack(45, false, true);
        verify(channel, never()).basicAck(45, false);
    }

    @Test
    void 本地最后可用配置持久化失败时应重新投递且不能回报加载成功() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshot snapshot = snapshot(payload);
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        ProtocolSnapshotRegistry registry = mock(ProtocolSnapshotRegistry.class);
        ConfigurationSnapshotStore store = mock(ConfigurationSnapshotStore.class);
        when(client.fetch(payload.releaseId())).thenReturn(snapshot);
        when(registry.activate(snapshot)).thenReturn(ProtocolSnapshotRegistry.ActivationResult.ACTIVATED);
        org.mockito.Mockito.doThrow(new ConfigurationSnapshotCacheException("缓存磁盘不可写"))
                .when(store).persist(snapshot);
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, registry, store);
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(46), channel);

        verify(client, never()).reportLoaded(payload.releaseId(), payload.contentHash());
        verify(channel).basicNack(46, false, true);
        verify(channel, never()).basicAck(46, false);
    }

    @Test
    void 拉取瞬时失败应重新投递而不是丢弃() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        when(client.fetch(payload.releaseId())).thenThrow(
                new ConfigurationFetchException("管理服务暂时不可用"));
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, mock(ProtocolSnapshotRegistry.class),
                mock(ConfigurationSnapshotStore.class));
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
                body -> envelope(payload), client, registry, mock(ConfigurationSnapshotStore.class));
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(43), channel);

        verify(registry, never()).activate(mismatched);
        verify(client).reportFailed(eq(payload.releaseId()),
                eq("SNAPSHOT_INVALID: 配置事件与管理服务快照定位信息不一致"));
        verify(channel).basicReject(43, false);
    }

    @Test
    void 永久快照失败应先回报脱敏截断摘要再进入死信() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(client.fetch(payload.releaseId())).thenThrow(new InvalidConfigurationSnapshotException(
                "\"token\":\"very-sensitive-value\" Authorization: Bearer second-sensitive-value\n"
                        + "损坏快照".repeat(200)));
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, mock(ProtocolSnapshotRegistry.class),
                mock(ConfigurationSnapshotStore.class),
                new ConfigurationDistributionMetrics(meterRegistry));
        Channel channel = mock(Channel.class);
        org.mockito.ArgumentCaptor<String> summary = org.mockito.ArgumentCaptor.forClass(String.class);

        consumer.onMessage(message(47), channel);

        verify(client).reportFailed(eq(payload.releaseId()), summary.capture());
        assertThat(summary.getValue())
                .startsWith("SNAPSHOT_INVALID: ")
                .contains("[REDACTED]")
                .doesNotContain("very-sensitive-value", "second-sensitive-value", "\n")
                .hasSizeLessThanOrEqualTo(500);
        assertThat(meterRegistry.get("mine.gateway.configuration.distribution")
                .tag("outcome", "permanent_failure").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("mine.gateway.configuration.stage")
                .tags("stage", "fetch", "result", "failure").counter().count()).isEqualTo(1);
        verify(channel).basicReject(47, false);
        verify(channel, never()).basicNack(47, false, true);
    }

    @Test
    void 永久失败状态暂时无法回报时应重投而不是提前死信() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(client.fetch(payload.releaseId())).thenThrow(
                new InvalidConfigurationSnapshotException("配置快照协议定义无法编译"));
        doThrow(new IllegalStateException("状态序列化器暂时不可用"))
                .when(client).reportFailed(eq(payload.releaseId()), org.mockito.ArgumentMatchers.anyString());
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, mock(ProtocolSnapshotRegistry.class),
                mock(ConfigurationSnapshotStore.class),
                new ConfigurationDistributionMetrics(meterRegistry));
        Channel channel = mock(Channel.class);

        consumer.onMessage(message(48), channel);

        verify(channel).basicNack(48, false, true);
        verify(channel, never()).basicReject(48, false);
        verify(channel, never()).basicAck(48, false);
        assertThat(meterRegistry.get("mine.gateway.configuration.distribution")
                .tag("outcome", "transient_failure").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("mine.gateway.configuration.stage")
                .tags("stage", "report", "result", "failure").counter().count()).isEqualTo(1);
    }

    @Test
    void 应记录低基数处理结果阶段结果和耗时指标() throws Exception {
        ConfigReleasePayload payload = payload();
        ConfigurationSnapshot snapshot = snapshot(payload);
        ConfigurationSnapshotClient client = mock(ConfigurationSnapshotClient.class);
        ProtocolSnapshotRegistry registry = mock(ProtocolSnapshotRegistry.class);
        ConfigurationSnapshotStore store = mock(ConfigurationSnapshotStore.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        when(client.fetch(payload.releaseId())).thenReturn(snapshot);
        when(registry.activate(snapshot)).thenReturn(ProtocolSnapshotRegistry.ActivationResult.ACTIVATED);
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> envelope(payload), client, registry, store,
                new ConfigurationDistributionMetrics(meterRegistry));

        consumer.onMessage(message(49), mock(Channel.class));

        assertThat(meterRegistry.get("mine.gateway.configuration.distribution")
                .tag("outcome", "loaded").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("mine.gateway.configuration.distribution.duration")
                .tag("outcome", "loaded").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("mine.gateway.configuration.stage")
                .tags("stage", "decode", "result", "success").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTag("productId"))
                        .as("指标不得包含产品或发布等高基数标签")
                        .isNull());
        assertThat(meterRegistry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTag("releaseId")).isNull());
    }

    @Test
    void 非法消息应直接进入死信() throws Exception {
        ConfigurationReleaseConsumer consumer = new ConfigurationReleaseConsumer(
                body -> {
                    throw new InvalidConfigurationMessageException("事件类型错误");
                },
                mock(ConfigurationSnapshotClient.class),
                mock(ProtocolSnapshotRegistry.class),
                mock(ConfigurationSnapshotStore.class));
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
