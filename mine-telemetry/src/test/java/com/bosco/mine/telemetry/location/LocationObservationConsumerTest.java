package com.bosco.mine.telemetry.location;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.model.location.LocationObservation;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationObservationConsumerTest {

    @Test
    void 已提交事实应先确认原消息再刷新缓存和唤醒Outbox() throws Exception {
        LocationFactWriter writer = mock(LocationFactWriter.class);
        LocationLatestCache cache = mock(LocationLatestCache.class);
        LocationObservationConsumer.CommittedEventObserver observer =
                mock(LocationObservationConsumer.CommittedEventObserver.class);
        Channel channel = mock(Channel.class);
        RealtimeLocationEvent event = realtimeEvent();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(writer.write(any())).thenReturn(
                new LocationFactWriter.WriteResult(LocationFactWriter.Outcome.STORED, event));

        new LocationObservationConsumer(
                writer, cache, observer, new LocationPipelineMetrics(registry, () -> 0D))
                .onMessage(message(41L), channel);

        var ordered = inOrder(channel, cache, observer);
        ordered.verify(channel).basicAck(41L, false);
        ordered.verify(cache).updateIfNewer(event);
        ordered.verify(observer).observe(event.eventId());
        assertThat(registry.get("mine.telemetry.location.ingest")
                .tags("result", "stored", "reason", "none")
                .counter().count()).isOne();
    }

    @Test
    void 缓存故障不得回滚事实或阻止可靠实时发布() throws Exception {
        LocationFactWriter writer = mock(LocationFactWriter.class);
        LocationLatestCache cache = mock(LocationLatestCache.class);
        LocationObservationConsumer.CommittedEventObserver observer =
                mock(LocationObservationConsumer.CommittedEventObserver.class);
        Channel channel = mock(Channel.class);
        RealtimeLocationEvent event = realtimeEvent();
        when(writer.write(any())).thenReturn(
                new LocationFactWriter.WriteResult(LocationFactWriter.Outcome.STORED, event));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(cache).updateIfNewer(event);

        new LocationObservationConsumer(writer, cache, observer)
                .onMessage(message(42L), channel);

        verify(channel).basicAck(42L, false);
        verify(observer).observe(event.eventId());
    }

    @Test
    void 隔离观测应拒绝且暂时数据库错误应重新入队() throws Exception {
        LocationLatestCache cache = mock(LocationLatestCache.class);
        LocationObservationConsumer.CommittedEventObserver observer =
                mock(LocationObservationConsumer.CommittedEventObserver.class);

        LocationFactWriter quarantiningWriter = mock(LocationFactWriter.class);
        when(quarantiningWriter.write(any())).thenReturn(
                new LocationFactWriter.WriteResult(
                        LocationFactWriter.Outcome.QUARANTINED, null));
        Channel rejected = mock(Channel.class);
        new LocationObservationConsumer(quarantiningWriter, cache, observer)
                .onMessage(message(43L), rejected);
        verify(rejected).basicReject(43L, false);
        verify(cache, never()).updateIfNewer(any());

        LocationFactWriter unavailableWriter = mock(LocationFactWriter.class);
        when(unavailableWriter.write(any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        Channel retried = mock(Channel.class);
        new LocationObservationConsumer(unavailableWriter, cache, observer)
                .onMessage(message(44L), retried);
        verify(retried).basicNack(44L, false, true);
    }

    @Test
    void 非法信封应直接拒绝且不进入写入器() throws Exception {
        LocationFactWriter writer = mock(LocationFactWriter.class);
        Channel channel = mock(Channel.class);
        Message invalid = MessageBuilder.withBody(new byte[] {1, 2, 3})
                .setDeliveryTag(45L)
                .setHeader("eventType", "telemetry.invalid")
                .setHeader("schemaVersion", 1)
                .build();

        new LocationObservationConsumer(
                writer, mock(LocationLatestCache.class), ignored -> { })
                .onMessage(invalid, channel);

        verify(channel).basicReject(45L, false);
        verify(writer, never()).write(any());
    }

    @Test
    void 身份可提取的非法坐标应先持久隔离再拒绝() throws Exception {
        LocationFactWriter writer = mock(LocationFactWriter.class);
        when(writer.quarantineInvalid(any())).thenReturn(new LocationFactWriter.WriteResult(
                LocationFactWriter.Outcome.QUARANTINED, null));
        Channel channel = mock(Channel.class);
        LocationObservation observation = observation();
        String body = """
                {"observationId":"%s","sourceDeviceId":"%s",\
                "tagCode":"tag-01","coordinateSystemCode":"source-mm",\
                "x":"not-a-number","y":2000,"bootId":"boot-a","sequence":1,\
                "occurredAt":"2026-07-18T01:00:00Z",\
                "receivedAt":"2026-07-18T01:00:01Z"}
                """.formatted(observation.observationId(), observation.sourceDeviceId());
        Message invalid = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(46L)
                .setMessageId(observation.observationId().toString())
                .setHeader("eventType", "location.observation")
                .setHeader("schemaVersion", 1)
                .build();

        new LocationObservationConsumer(
                writer, mock(LocationLatestCache.class), ignored -> { })
                .onMessage(invalid, channel);

        verify(writer).quarantineInvalid(any());
        verify(channel).basicReject(46L, false);
        verify(writer, never()).write(any());
    }

    private Message message(long deliveryTag) throws Exception {
        LocationObservation observation = observation();
        return MessageBuilder.withBody(JsonMapper.builder().findAndAddModules().build()
                        .writeValueAsBytes(observation))
                .setDeliveryTag(deliveryTag)
                .setMessageId(observation.observationId().toString())
                .setHeader("eventType", "location.observation")
                .setHeader("schemaVersion", 1)
                .build();
    }

    private LocationObservation observation() {
        return new LocationObservation(
                uuid(10), uuid(11), "tag-01", "source-mm",
                1000D, 2000D, 300D, 5D, 180D, 2D,
                "boot-a", 1L,
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T01:00:01Z"));
    }

    private RealtimeLocationEvent realtimeEvent() {
        return new RealtimeLocationEvent(
                uuid(10), uuid(12), uuid(13), uuid(11), uuid(14), "tag-01",
                uuid(15), uuid(16), null, 21L,
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T01:00:01Z"),
                1D, 2D, 0.3D, 5D, 180D, 2D,
                RealtimeLocationEvent.QualityStatus.GOOD, null);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7400-0000-7000-8000-%012d".formatted(suffix));
    }
}
