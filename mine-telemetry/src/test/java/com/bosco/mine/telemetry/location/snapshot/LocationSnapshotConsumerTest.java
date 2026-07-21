package com.bosco.mine.telemetry.location.snapshot;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.location.LocationRuntimeChangedPayload;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocationSnapshotConsumerTest {

    private static final UUID EVENT_ID = uuid(301);
    private static final UUID SNAPSHOT_ID = uuid(302);
    private static final UUID SITE_ID = uuid(303);

    @Test
    void 成功激活后应确认消息() throws Exception {
        LocationSnapshotLoader loader = mock(LocationSnapshotLoader.class);
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new LocationSnapshotConsumer(loader, new LocationSnapshotMetrics(meters))
                .onMessage(message(31L), channel);

        verify(loader).apply(EVENT_ID, SITE_ID, payload());
        verify(channel).basicAck(31L, false);
        assertThat(meters.get("mine.telemetry.location.snapshot.result")
                .tag("outcome", "loaded").counter().count()).isOne();
    }

    @Test
    void 永久快照错误应拒绝且暂时错误应重新入队() throws Exception {
        LocationSnapshotLoader permanentLoader = mock(LocationSnapshotLoader.class);
        doThrow(new PermanentLocationSnapshotException("bad snapshot"))
                .when(permanentLoader).apply(EVENT_ID, SITE_ID, payload());
        Channel permanentChannel = mock(Channel.class);
        new LocationSnapshotConsumer(permanentLoader,
                new LocationSnapshotMetrics(new SimpleMeterRegistry()))
                .onMessage(message(32L), permanentChannel);
        verify(permanentChannel).basicReject(32L, false);

        LocationSnapshotLoader transientLoader = mock(LocationSnapshotLoader.class);
        doThrow(new IllegalStateException("server unavailable"))
                .when(transientLoader).apply(EVENT_ID, SITE_ID, payload());
        Channel transientChannel = mock(Channel.class);
        new LocationSnapshotConsumer(transientLoader,
                new LocationSnapshotMetrics(new SimpleMeterRegistry()))
                .onMessage(message(33L), transientChannel);
        verify(transientChannel).basicNack(33L, false, true);
    }

    @Test
    void 非法事件应直接拒绝且不重试() throws Exception {
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Message invalid = MessageBuilder.withBody("not-json".getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(34L)
                .build();

        new LocationSnapshotConsumer(
                mock(LocationSnapshotLoader.class), new LocationSnapshotMetrics(meters))
                .onMessage(invalid, channel);

        verify(channel).basicReject(34L, false);
        assertThat(meters.get("mine.telemetry.location.snapshot.result")
                .tag("outcome", "invalid_message").counter().count()).isOne();
    }

    private Message message(long deliveryTag) throws Exception {
        MessageEnvelope<LocationRuntimeChangedPayload> envelope = new MessageEnvelope<>(
                EVENT_ID, MessageType.of("location.runtime.changed"), 1,
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T01:00:01Z"),
                "mine-server", "trace-location", SNAPSHOT_ID.toString(),
                SITE_ID.toString(), "mine-local", payload());
        return MessageBuilder.withBody(
                        JsonMapper.builder().findAndAddModules().build()
                                .writeValueAsBytes(envelope))
                .setDeliveryTag(deliveryTag)
                .build();
    }

    private LocationRuntimeChangedPayload payload() {
        return new LocationRuntimeChangedPayload(
                SNAPSHOT_ID, 3, "sha256:" + "a".repeat(64));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7200-0000-7000-8000-%012d".formatted(suffix));
    }
}
