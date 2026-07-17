package com.bosco.mine.telemetry.consumer;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.alarm.AlarmPolicy;
import com.bosco.mine.telemetry.alarm.AlarmPolicySource;
import com.bosco.mine.telemetry.alarm.outbox.AlarmOutboxEventObserver;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import com.bosco.mine.telemetry.quality.DataQualityEvaluator;
import com.bosco.mine.telemetry.quality.DataQualityPolicy;
import com.bosco.mine.telemetry.quality.DataQualityPolicySource;
import com.bosco.mine.telemetry.quality.QualityEvaluationCoverage;
import com.bosco.mine.telemetry.quality.QualityEvidence;
import com.bosco.mine.telemetry.quality.QualityEvidenceObserver;
import com.bosco.mine.telemetry.quality.QualityRuleCode;
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
import java.util.Set;
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
    void 应先提交数据库持久读模型再刷新Redis最后确认消息() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        NormalizedTelemetryConsumer.RealtimePublisher realtimePublisher =
                mock(NormalizedTelemetryConsumer.RealtimePublisher.class);
        TelemetrySequenceGapObserver gapObserver = mock(TelemetrySequenceGapObserver.class);
        Channel channel = mock(Channel.class);
        when(latestValues.updateEventIfNewer(any())).thenReturn(true);
        when(batchWriter.write(any(), any(), any(), any())).thenReturn(
                new TelemetryBatchWriter.WriteResult(
                        1, 2, Set.of(EVENT_ID), Map.of(EVENT_ID, 41L),
                        List.of(new TelemetryBatchWriter.SequenceGap(
                                EVENT_ID, DEVICE_ID, "boot-a", 3L, 7L, 3L
                        ))
                )
        );
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, realtimePublisher, gapObserver
        );
        Message message = message(17L);

        consumer.onMessageBatch(List.of(message), channel);

        ArgumentCaptor<List<TelemetrySample>> samples = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(
                latestValues, batchWriter, realtimePublisher, gapObserver, channel
        );
        order.verify(batchWriter).write(samples.capture(), any(), any(), any());
        order.verify(gapObserver).observe(any());
        order.verify(latestValues).updateEventIfNewer(any());
        ArgumentCaptor<RealtimeTelemetryEvent> realtimeEvent =
                ArgumentCaptor.forClass(RealtimeTelemetryEvent.class);
        order.verify(realtimePublisher).publish(realtimeEvent.capture());
        order.verify(channel).basicAck(17L, false);
        assertThat(samples.getValue()).extracting(TelemetrySample::propertyId)
                .containsExactlyInAnyOrder("engine.rpm", "engine.temperature");
        assertThat(realtimeEvent.getValue().sourceSequence()).isEqualTo(41L);
        assertThat(realtimeEvent.getValue().values()).containsEntry("engine.rpm", 1280D)
                .containsEntry("engine.temperature", 83.5D);
    }

    @Test
    void 数据库失败时不得更新Redis并应将消息重新入队() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        Channel channel = mock(Channel.class);
        doThrow(new TelemetryBatchWriter.TelemetryStorageException("测试失败", new RuntimeException()))
                .when(batchWriter).write(any(), any(), any(), any());
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, event -> { }
        );

        consumer.onMessageBatch(List.of(message(18L)), channel);

        verify(latestValues, org.mockito.Mockito.never()).updateEventIfNewer(any());
        verify(channel).basicNack(18L, false, true);
    }

    @Test
    void 非法消息应拒绝且不得重新入队() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        Channel channel = mock(Channel.class);
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, event -> { }
        );
        Message invalid = new Message("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        invalid.getMessageProperties().setDeliveryTag(19L);

        consumer.onMessageBatch(List.of(invalid), channel);

        verify(channel).basicReject(19L, false);
        verify(batchWriter, org.mockito.Mockito.never()).write(any(), any(), any(), any());
    }

    @Test
    void 实时事件发布失败时数据库和Redis保持幂等并将原消息重新入队() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        NormalizedTelemetryConsumer.RealtimePublisher realtimePublisher =
                mock(NormalizedTelemetryConsumer.RealtimePublisher.class);
        Channel channel = mock(Channel.class);
        when(latestValues.updateEventIfNewer(any())).thenReturn(true);
        when(batchWriter.write(any(), any(), any(), any())).thenReturn(new TelemetryBatchWriter.WriteResult(
                1, 2, Set.of(EVENT_ID), Map.of(EVENT_ID, 42L)
        ));
        doThrow(new IllegalStateException("Broker confirm 失败"))
                .when(realtimePublisher).publish(any());
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, realtimePublisher
        );

        consumer.onMessageBatch(List.of(message(20L)), channel);

        InOrder order = inOrder(batchWriter, latestValues, realtimePublisher, channel);
        order.verify(batchWriter).write(any(), any(), any(), any());
        order.verify(latestValues).updateEventIfNewer(any());
        order.verify(realtimePublisher).publish(any());
        order.verify(channel).basicNack(20L, false, true);
        verify(channel, org.mockito.Mockito.never()).basicAck(20L, false);
    }

    @Test
    void 缺口指标观察失败时不得阻塞遥测存储和消息确认() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        NormalizedTelemetryConsumer.RealtimePublisher realtimePublisher =
                mock(NormalizedTelemetryConsumer.RealtimePublisher.class);
        TelemetrySequenceGapObserver gapObserver = mock(TelemetrySequenceGapObserver.class);
        Channel channel = mock(Channel.class);
        TelemetryBatchWriter.SequenceGap gap = new TelemetryBatchWriter.SequenceGap(
                EVENT_ID, DEVICE_ID, "boot-a", 3L, 7L, 3L
        );
        when(latestValues.updateEventIfNewer(any())).thenReturn(true);
        when(batchWriter.write(any(), any(), any(), any())).thenReturn(new TelemetryBatchWriter.WriteResult(
                1, 2, Set.of(EVENT_ID), Map.of(EVENT_ID, 43L), List.of(gap)
        ));
        doThrow(new IllegalStateException("测试指标异常")).when(gapObserver).observe(gap);
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, realtimePublisher, gapObserver
        );

        consumer.onMessageBatch(List.of(message(21L)), channel);

        verify(gapObserver).observe(gap);
        verify(latestValues).updateEventIfNewer(any());
        verify(realtimePublisher).publish(any());
        verify(channel).basicAck(21L, false);
        verify(channel, org.mockito.Mockito.never()).basicNack(21L, false, true);
    }

    @Test
    void 应批量读取策略并持久化属性级质量且只统计已提交证据() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        NormalizedTelemetryConsumer.RealtimePublisher realtimePublisher =
                mock(NormalizedTelemetryConsumer.RealtimePublisher.class);
        TelemetrySequenceGapObserver gapObserver = mock(TelemetrySequenceGapObserver.class);
        DataQualityPolicySource policySource = mock(DataQualityPolicySource.class);
        QualityEvidenceObserver qualityObserver = mock(QualityEvidenceObserver.class);
        Channel channel = mock(Channel.class);
        DataQualityPolicy policy = DataQualityPolicy.from(
                UUID.fromString("019f6e00-0000-7000-8000-000000000140"),
                7L,
                "sha256:" + "a".repeat(64),
                Map.of("minimum.engine.rpm", 1300)
        );
        when(policySource.findByDeviceIds(any())).thenReturn(Map.of(DEVICE_ID, policy));
        when(batchWriter.write(any(), any(), any(), any())).thenAnswer(invocation ->
                new TelemetryBatchWriter.WriteResult(
                        1,
                        2,
                        Set.of(EVENT_ID),
                        Map.of(EVENT_ID, 44L),
                        List.of(),
                        invocation.getArgument(1)
                ));
        when(latestValues.updateEventIfNewer(any())).thenReturn(true);
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues,
                batchWriter,
                realtimePublisher,
                gapObserver,
                policySource,
                new DataQualityEvaluator(),
                qualityObserver
        );

        consumer.onMessageBatch(List.of(message(22L)), channel);

        ArgumentCaptor<List<TelemetrySample>> samples = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<QualityEvidence>> evidence = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, QualityEvaluationCoverage>> coverage =
                ArgumentCaptor.forClass(Map.class);
        verify(batchWriter).write(
                samples.capture(), evidence.capture(), any(), coverage.capture());
        assertThat(samples.getValue())
                .filteredOn(sample -> sample.propertyId().equals("engine.rpm"))
                .singleElement()
                .satisfies(sample -> assertThat(sample.quality()).isEqualTo(Quality.BAD));
        assertThat(samples.getValue())
                .filteredOn(sample -> sample.propertyId().equals("engine.temperature"))
                .singleElement()
                .satisfies(sample -> assertThat(sample.quality()).isEqualTo(Quality.GOOD));
        assertThat(evidence.getValue()).singleElement().satisfies(item -> {
            assertThat(item.ruleCode()).isEqualTo(QualityRuleCode.BELOW_MINIMUM);
            assertThat(item.propertyId()).isEqualTo("engine.rpm");
        });
        assertThat(coverage.getValue().get(EVENT_ID).covers(
                QualityRuleCode.BELOW_MINIMUM, "engine.rpm")).isTrue();
        assertThat(coverage.getValue().get(EVENT_ID).covers(
                QualityRuleCode.BELOW_MINIMUM, "engine.temperature")).isFalse();
        verify(qualityObserver).observe(evidence.getValue().getFirst());
        ArgumentCaptor<RealtimeTelemetryEvent> realtime =
                ArgumentCaptor.forClass(RealtimeTelemetryEvent.class);
        verify(realtimePublisher).publish(realtime.capture());
        assertThat(realtime.getValue().quality()).isEqualTo(Quality.BAD);
        assertThat(realtime.getValue().qualities())
                .containsEntry("engine.rpm", Quality.BAD)
                .containsEntry("engine.temperature", Quality.GOOD);
        verify(channel).basicAck(22L, false);
    }

    @Test
    void 本地策略镜像暂时不可读时应重新投递且不得写入遥测() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        DataQualityPolicySource policySource = mock(DataQualityPolicySource.class);
        Channel channel = mock(Channel.class);
        when(policySource.findByDeviceIds(any()))
                .thenThrow(new IllegalStateException("测试策略镜像不可用"));
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues,
                batchWriter,
                event -> { },
                ignored -> { },
                policySource,
                new DataQualityEvaluator(),
                ignored -> { }
        );

        consumer.onMessageBatch(List.of(message(23L)), channel);

        verify(channel).basicNack(23L, false, true);
        verify(batchWriter, org.mockito.Mockito.never()).write(any(), any(), any(), any());
        verify(latestValues, org.mockito.Mockito.never()).updateEventIfNewer(any());
    }

    @Test
    void 应把最后正确告警策略交给事务写器并在提交后精确唤醒Outbox() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        DataQualityPolicySource qualitySource = mock(DataQualityPolicySource.class);
        AlarmPolicySource alarmSource = mock(AlarmPolicySource.class);
        AlarmOutboxEventObserver outboxObserver = mock(AlarmOutboxEventObserver.class);
        Channel channel = mock(Channel.class);
        UUID outboxEventId = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e799");
        AlarmPolicy policy = new AlarmPolicy(
                UUID.fromString("019f6e00-0000-7000-8000-000000000180"),
                8L,
                "sha256:" + "b".repeat(64),
                List.of(new AlarmPolicy.Rule(
                        "engine-rpm-high", "发动机转速过高",
                        AlarmPolicy.SourceType.NUMERIC, AlarmPolicy.Severity.CRITICAL,
                        0, 0, 60, true, "engine.rpm", AlarmPolicy.Operator.GT,
                        new java.math.BigDecimal("1200"),
                        new java.math.BigDecimal("1100"), null)));
        when(qualitySource.findByDeviceIds(any())).thenReturn(Map.of());
        when(alarmSource.findByDeviceIds(any())).thenReturn(Map.of(DEVICE_ID, policy));
        when(batchWriter.write(any(), any(), any(), any())).thenReturn(
                new TelemetryBatchWriter.WriteResult(
                        1, 2, Set.of(EVENT_ID), Map.of(EVENT_ID, 45L),
                        List.of(), List.of(), Map.of(), List.of(outboxEventId)));
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, event -> { }, ignored -> { },
                qualitySource, new DataQualityEvaluator(), ignored -> { },
                alarmSource, outboxObserver);

        consumer.onMessageBatch(List.of(message(24L)), channel);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, AlarmPolicy>> policies = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, QualityEvaluationCoverage>> qualityEvaluated =
                ArgumentCaptor.forClass(Map.class);
        verify(batchWriter).write(any(), any(), policies.capture(), qualityEvaluated.capture());
        assertThat(policies.getValue()).containsEntry(DEVICE_ID, policy);
        assertThat(qualityEvaluated.getValue()).isEmpty();
        InOrder order = inOrder(batchWriter, outboxObserver, channel);
        order.verify(batchWriter).write(any(), any(), any(), any());
        order.verify(outboxObserver).observe(outboxEventId);
        order.verify(channel).basicAck(24L, false);
    }

    @Test
    void 告警策略镜像暂时不可读时应重新投递且不得写入遥测() throws Exception {
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        DataQualityPolicySource qualitySource = mock(DataQualityPolicySource.class);
        AlarmPolicySource alarmSource = mock(AlarmPolicySource.class);
        Channel channel = mock(Channel.class);
        when(qualitySource.findByDeviceIds(any())).thenReturn(Map.of());
        when(alarmSource.findByDeviceIds(any()))
                .thenThrow(new IllegalStateException("测试告警策略镜像不可用"));
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, event -> { }, ignored -> { },
                qualitySource, new DataQualityEvaluator(), ignored -> { },
                alarmSource, ignored -> { });

        consumer.onMessageBatch(List.of(message(25L)), channel);

        verify(channel).basicNack(25L, false, true);
        verify(batchWriter, org.mockito.Mockito.never()).write(any(), any(), any(), any());
    }

    @Test
    void 同批非法遥测被拒绝后不得把其告警策略传给剩余有效事件() throws Exception {
        UUID secondEventId = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e762");
        UUID secondDeviceId = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e75e");
        LatestValueRepository latestValues = mock(LatestValueRepository.class);
        TelemetryBatchWriter batchWriter = mock(TelemetryBatchWriter.class);
        DataQualityPolicySource qualitySource = mock(DataQualityPolicySource.class);
        DataQualityEvaluator evaluator = mock(DataQualityEvaluator.class);
        AlarmPolicySource alarmSource = mock(AlarmPolicySource.class);
        Channel channel = mock(Channel.class);
        AlarmPolicy policy = new AlarmPolicy(
                UUID.fromString("019f6e00-0000-7000-8000-000000000180"), 8L,
                "sha256:" + "b".repeat(64),
                List.of(new AlarmPolicy.Rule(
                        "engine-rpm-high", "发动机转速过高",
                        AlarmPolicy.SourceType.NUMERIC, AlarmPolicy.Severity.CRITICAL,
                        0, 0, 60, true, "engine.rpm", AlarmPolicy.Operator.GT,
                        new java.math.BigDecimal("1200"),
                        new java.math.BigDecimal("1100"), null)));
        when(qualitySource.findByDeviceIds(any())).thenReturn(Map.of());
        when(alarmSource.findByDeviceIds(any())).thenReturn(Map.of(
                DEVICE_ID, policy, secondDeviceId, policy));
        DataQualityEvaluator realEvaluator = new DataQualityEvaluator();
        when(evaluator.evaluate(any(UUID.class), any(UUID.class), any(UnifiedDeviceMessage.class)))
                .thenAnswer(invocation -> {
                    UUID eventId = invocation.getArgument(0);
                    UUID deviceId = invocation.getArgument(1);
                    UnifiedDeviceMessage payload = invocation.getArgument(2);
                    if (deviceId.equals(secondDeviceId)) {
                        throw new IllegalArgumentException("测试非法遥测");
                    }
                    return realEvaluator.evaluate(eventId, deviceId, payload);
                });
        when(batchWriter.write(any(), any(), any(), any())).thenReturn(
                new TelemetryBatchWriter.WriteResult(
                        1, 2, Set.of(EVENT_ID), Map.of(EVENT_ID, 46L)));
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, event -> { }, ignored -> { },
                qualitySource, evaluator, ignored -> { }, alarmSource, ignored -> { });

        consumer.onMessageBatch(List.of(
                message(26L), message(27L, secondEventId, secondDeviceId)), channel);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, AlarmPolicy>> policies = ArgumentCaptor.forClass(Map.class);
        verify(batchWriter).write(any(), any(), policies.capture(), any());
        assertThat(policies.getValue()).containsOnlyKeys(DEVICE_ID);
        verify(channel).basicReject(27L, false);
        verify(channel).basicAck(26L, false);
    }

    private Message message(long deliveryTag) throws Exception {
        return message(deliveryTag, EVENT_ID, DEVICE_ID);
    }

    private Message message(long deliveryTag, UUID eventId, UUID deviceId) throws Exception {
        UnifiedDeviceMessage payload = new UnifiedDeviceMessage(
                eventId,
                MessageKind.TELEMETRY,
                "haul-truck",
                deviceId.toString(),
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
                eventId,
                MessageType.of("telemetry.normalized"),
                1,
                payload.occurredAt(),
                payload.receivedAt(),
                "mine-gateway",
                "trace-001",
                null,
                deviceId.toString(),
                "mine-local",
                payload
        );
        Message message = new Message(jsonMapper.writeValueAsBytes(envelope));
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
    }
}
