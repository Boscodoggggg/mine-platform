package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 校验策略变更消费者对成功、永久错误和暂时错误的 RabbitMQ 确认语义。
 */
class StrategyRuntimeConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("019f6e00-0000-7000-8000-000000000010");

    @Test
    void 成功应用后应确认消息() throws Exception {
        StrategySnapshotLoader loader = mock(StrategySnapshotLoader.class);
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new StrategyRuntimeConsumer(loader, new StrategyDistributionMetrics(meterRegistry))
                .onMessage(message(11L), channel);

        verify(loader).apply(EVENT_ID, payload());
        verify(channel).basicAck(11L, false);
        assertThat(meterRegistry.get("mine.telemetry.strategy.distribution")
                .tag("outcome", "loaded").counter().count()).isOne();
        assertThat(meterRegistry.get("mine.telemetry.strategy.distribution.duration")
                .tag("outcome", "loaded").timer().count()).isOne();
        assertThat(meterRegistry.get("mine.telemetry.strategy.stage")
                .tags("stage", "decode", "result", "success").counter().count()).isOne();
        assertThat(meterRegistry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTag("productId")).isNull();
            assertThat(meter.getId().getTag("deviceId")).isNull();
            assertThat(meter.getId().getTag("snapshotId")).isNull();
        });
    }

    @Test
    void 永久快照错误应拒绝且不重新入队() throws Exception {
        StrategySnapshotLoader loader = mock(StrategySnapshotLoader.class);
        doThrow(new PermanentStrategySnapshotException("hash mismatch"))
                .when(loader).apply(EVENT_ID, payload());
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new StrategyRuntimeConsumer(loader, new StrategyDistributionMetrics(meterRegistry))
                .onMessage(message(12L), channel);

        verify(channel).basicReject(12L, false);
        assertThat(meterRegistry.get("mine.telemetry.strategy.distribution")
                .tag("outcome", "permanent_failure").counter().count()).isOne();
    }

    @Test
    void 暂时存储或网络错误应重新入队() throws Exception {
        StrategySnapshotLoader loader = mock(StrategySnapshotLoader.class);
        doThrow(new IllegalStateException("server unavailable"))
                .when(loader).apply(EVENT_ID, payload());
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new StrategyRuntimeConsumer(loader, new StrategyDistributionMetrics(meterRegistry))
                .onMessage(message(13L), channel);

        verify(channel).basicNack(13L, false, true);
        assertThat(meterRegistry.get("mine.telemetry.strategy.distribution")
                .tag("outcome", "transient_failure").counter().count()).isOne();
    }

    @Test
    void 非法事件应记录解码失败并拒绝消息() throws Exception {
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Message invalid = MessageBuilder.withBody("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setDeliveryTag(14L)
                .build();

        new StrategyRuntimeConsumer(
                mock(StrategySnapshotLoader.class),
                new StrategyDistributionMetrics(meterRegistry)
        ).onMessage(invalid, channel);

        verify(channel).basicReject(14L, false);
        assertThat(meterRegistry.get("mine.telemetry.strategy.distribution")
                .tag("outcome", "invalid_message").counter().count()).isOne();
        assertThat(meterRegistry.get("mine.telemetry.strategy.stage")
                .tags("stage", "decode", "result", "failure").counter().count()).isOne();
    }

    private Message message(long deliveryTag) throws Exception {
        MessageEnvelope<StrategyRuntimeChangedPayload> envelope = new MessageEnvelope<>(
                EVENT_ID,
                MessageType.of("strategy.runtime.changed"),
                1,
                Instant.parse("2026-07-17T01:00:00Z"),
                Instant.parse("2026-07-17T01:00:01Z"),
                "mine-server",
                "trace-1",
                payload().snapshotId().toString(),
                payload().productId().toString(),
                "mine-local",
                payload()
        );
        return MessageBuilder.withBody(
                        JsonMapper.builder().findAndAddModules().build().writeValueAsBytes(envelope))
                .setDeliveryTag(deliveryTag)
                .build();
    }

    private StrategyRuntimeChangedPayload payload() {
        return new StrategyRuntimeChangedPayload(
                UUID.fromString("019f6e00-0000-7000-8000-000000000020"),
                UUID.fromString("019f6e00-0000-7000-8000-000000000030"),
                "DATA_QUALITY", 7, 1, "sha256:" + "a".repeat(64));
    }
}
