package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

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

        new StrategyRuntimeConsumer(loader).onMessage(message(11L), channel);

        verify(loader).apply(EVENT_ID, payload());
        verify(channel).basicAck(11L, false);
    }

    @Test
    void 永久快照错误应拒绝且不重新入队() throws Exception {
        StrategySnapshotLoader loader = mock(StrategySnapshotLoader.class);
        doThrow(new PermanentStrategySnapshotException("hash mismatch"))
                .when(loader).apply(EVENT_ID, payload());
        Channel channel = mock(Channel.class);

        new StrategyRuntimeConsumer(loader).onMessage(message(12L), channel);

        verify(channel).basicReject(12L, false);
    }

    @Test
    void 暂时存储或网络错误应重新入队() throws Exception {
        StrategySnapshotLoader loader = mock(StrategySnapshotLoader.class);
        doThrow(new IllegalStateException("server unavailable"))
                .when(loader).apply(EVENT_ID, payload());
        Channel channel = mock(Channel.class);

        new StrategyRuntimeConsumer(loader).onMessage(message(13L), channel);

        verify(channel).basicNack(13L, false, true);
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
