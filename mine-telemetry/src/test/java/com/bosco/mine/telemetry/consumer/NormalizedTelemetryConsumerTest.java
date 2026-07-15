package com.bosco.mine.telemetry.consumer;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验标准化遥测批消费者的存储顺序和 RabbitMQ 手工确认语义。
 */
class NormalizedTelemetryConsumerTest {

    private static final UUID EVENT_ID = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e761");
    private static final UUID DEVICE_ID = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e75d");
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 应先更新Redis再写数据库最后确认消息() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        Channel channel = mock(Channel.class);
        when(latestValues.updateEventIfNewer(any())).thenReturn(true);
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(latestValues, batchWriter);
        Message message = message(17L);

        consumer.onMessageBatch(List.of(message), channel);

        ArgumentCaptor<List<TelemetrySample>> samples = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(latestValues, batchWriter, channel);
        order.verify(latestValues).updateEventIfNewer(any());
        order.verify(batchWriter).write(samples.capture());
        order.verify(channel).basicAck(17L, false);
        assertThat(samples.getValue()).extracting(TelemetrySample::propertyId)
                .containsExactlyInAnyOrder("engine.rpm", "engine.temperature");
    }

    @Test
    void 数据库失败时应保留Redis读模型并将消息重新入队() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        Channel channel = mock(Channel.class);
        when(latestValues.updateEventIfNewer(any())).thenReturn(true);
        doThrow(new TelemetryBatchWriter.TelemetryStorageException("测试失败", new RuntimeException()))
                .when(batchWriter).write(any());
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(latestValues, batchWriter);

        consumer.onMessageBatch(List.of(message(18L)), channel);

        verify(latestValues).updateEventIfNewer(any());
        verify(channel).basicNack(18L, false, true);
    }

    @Test
    void 非法消息应拒绝且不得重新入队() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        Channel channel = mock(Channel.class);
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(latestValues, batchWriter);
        Message invalid = new Message("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        invalid.getMessageProperties().setDeliveryTag(19L);

        consumer.onMessageBatch(List.of(invalid), channel);

        verify(channel).basicReject(19L, false);
        verify(batchWriter, org.mockito.Mockito.never()).write(any());
    }

    private Message message(long deliveryTag) throws Exception {
        UnifiedDeviceMessage payload = new UnifiedDeviceMessage(
                EVENT_ID,
                MessageKind.TELEMETRY,
                "haul-truck",
                DEVICE_ID.toString(),
                "v1",
                "boot-a",
                7L,
                Instant.parse("2026-07-15T03:00:00Z"),
                Instant.parse("2026-07-15T03:00:02Z"),
                false,
                new TelemetryPayload(new LinkedHashMap<>(Map.ofEntries(
                        Map.entry("engine.rpm", 1280),
                        Map.entry("engine.temperature", 83.5)
                ))),
                Quality.GOOD,
                null
        );
        MessageEnvelope<UnifiedDeviceMessage> envelope = new MessageEnvelope<>(
                EVENT_ID,
                MessageType.of("telemetry.normalized"),
                1,
                payload.occurredAt(),
                payload.receivedAt(),
                "mine-gateway",
                "trace-001",
                null,
                DEVICE_ID.toString(),
                "mine-local",
                payload
        );
        Message message = new Message(jsonMapper.writeValueAsBytes(envelope));
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
    }
}
