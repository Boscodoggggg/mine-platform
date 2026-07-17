package com.bosco.mine.telemetry.consumer;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import com.bosco.mine.telemetry.quality.DataQualityEvaluation;
import com.bosco.mine.telemetry.quality.DataQualityEvaluator;
import com.bosco.mine.telemetry.quality.DataQualityPolicy;
import com.bosco.mine.telemetry.quality.DataQualityPolicySource;
import com.bosco.mine.telemetry.quality.QualityEvidence;
import com.bosco.mine.telemetry.quality.QualityEvidenceObserver;
import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareBatchMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 批量消费 gateway 输出的标准化遥测，先事务持久化历史和最新读模型，再刷新 Redis 加速层。
 */
public final class NormalizedTelemetryConsumer implements ChannelAwareBatchMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizedTelemetryConsumer.class);
    private static final TypeReference<MessageEnvelope<UnifiedDeviceMessage>> ENVELOPE_TYPE =
            new TypeReference<>() { };

    private final LatestValueRepository latestValues;
    private final TelemetryBatchWriter batchWriter;
    private final RealtimePublisher realtimePublisher;
    private final TelemetrySequenceGapObserver sequenceGapObserver;
    private final DataQualityPolicySource qualityPolicySource;
    private final DataQualityEvaluator qualityEvaluator;
    private final QualityEvidenceObserver qualityEvidenceObserver;
    private final JsonMapper jsonMapper;

    /**
     * 创建标准化遥测批消费者。
     *
     * @param latestValues Redis 实时最新值仓储
     * @param batchWriter  TimescaleDB 事务批写器
     * @param realtimePublisher 持久化完成后的实时事件发布器
     */
    public NormalizedTelemetryConsumer(
            LatestValueRepository latestValues,
            TelemetryBatchWriter batchWriter,
            RealtimePublisher realtimePublisher) {
        this(latestValues, batchWriter, realtimePublisher, ignored -> { });
    }

    /**
     * 创建带设备序列缺口观察器的标准化遥测批消费者。
     *
     * @param latestValues Redis 实时最新值仓储
     * @param batchWriter TimescaleDB 事务批写器
     * @param realtimePublisher 持久化完成后的实时事件发布器
     * @param sequenceGapObserver 持久顺序状态确认后的序列缺口观察器
     */
    public NormalizedTelemetryConsumer(
            LatestValueRepository latestValues,
            TelemetryBatchWriter batchWriter,
            RealtimePublisher realtimePublisher,
            TelemetrySequenceGapObserver sequenceGapObserver) {
        this(
                latestValues,
                batchWriter,
                realtimePublisher,
                sequenceGapObserver,
                ignored -> Map.of(),
                new DataQualityEvaluator(),
                ignored -> { }
        );
    }

    /**
     * 创建执行最后正确策略并观察已提交质量证据的标准化遥测批消费者。
     *
     * @param latestValues Redis 实时最新值仓储
     * @param batchWriter TimescaleDB 事务批写器
     * @param realtimePublisher 持久化完成后的实时事件发布器
     * @param sequenceGapObserver 设备序列缺口观察器
     * @param qualityPolicySource 数据质量策略批量读取边界
     * @param qualityEvaluator 属性级数据质量判定器
     * @param qualityEvidenceObserver 已提交质量证据观察器
     */
    public NormalizedTelemetryConsumer(
            LatestValueRepository latestValues,
            TelemetryBatchWriter batchWriter,
            RealtimePublisher realtimePublisher,
            TelemetrySequenceGapObserver sequenceGapObserver,
            DataQualityPolicySource qualityPolicySource,
            DataQualityEvaluator qualityEvaluator,
            QualityEvidenceObserver qualityEvidenceObserver) {
        this.latestValues = Objects.requireNonNull(latestValues, "最新值仓储不能为空");
        this.batchWriter = Objects.requireNonNull(batchWriter, "历史批写器不能为空");
        this.realtimePublisher = Objects.requireNonNull(realtimePublisher, "实时事件发布器不能为空");
        this.sequenceGapObserver = Objects.requireNonNull(
                sequenceGapObserver, "序列缺口观察器不能为空"
        );
        this.qualityPolicySource = Objects.requireNonNull(
                qualityPolicySource, "数据质量策略源不能为空");
        this.qualityEvaluator = Objects.requireNonNull(
                qualityEvaluator, "数据质量判定器不能为空");
        this.qualityEvidenceObserver = Objects.requireNonNull(
                qualityEvidenceObserver, "质量证据观察器不能为空");
        this.jsonMapper = JsonMapper.builder().findAndAddModules().build();
    }

    /**
     * 处理一批 RabbitMQ 标准化遥测。不可解析消息进入死信，暂时性存储失败的消息重新入队。
     *
     * @param messages 本次消费者批次
     * @param channel  平台 vhost 消费通道
     */
    @Override
    public void onMessageBatch(List<Message> messages, Channel channel) {
        Objects.requireNonNull(messages, "消息批次不能为空");
        Objects.requireNonNull(channel, "RabbitMQ 通道不能为空");
        List<DecodedMessage> decoded = decodeValidMessages(messages, channel);
        List<PendingMessage> pending = evaluateValidMessages(decoded, channel);
        if (pending.isEmpty()) {
            return;
        }

        List<TelemetrySample> samples = pending.stream()
                .flatMap(item -> item.samples().stream())
                .toList();
        List<QualityEvidence> evidence = pending.stream()
                .flatMap(item -> item.evidence().stream())
                .toList();
        try {
            TelemetryBatchWriter.WriteResult result = batchWriter.write(samples, evidence);
            observeSequenceGaps(result.sequenceGaps());
            observeQualityEvidence(result.qualityEvidence());
            pending.stream()
                    .filter(item -> result.latestEventIds().contains(
                            item.samples().getFirst().eventId()
                    ))
                    .forEach(item -> latestValues.updateEventIfNewer(
                            latestSamples(result, item)));
            pending.stream()
                    .filter(item -> result.latestEventIds().contains(
                            item.samples().getFirst().eventId()
                    ))
                    .map(item -> realtimeEvent(
                            latestSamples(result, item),
                            result.latestEventIngestIds().get(
                                    item.samples().getFirst().eventId()
                            )
                    ))
                    .forEach(realtimePublisher::publish);
        } catch (RuntimeException exception) {
            LOGGER.error("遥测批次存储失败，{} 条 RabbitMQ 消息将重新投递", pending.size(), exception);
            pending.forEach(item -> nack(item.message(), channel));
            return;
        }
        pending.forEach(item -> ack(item.message(), channel));
    }

    private List<TelemetrySample> latestSamples(
            TelemetryBatchWriter.WriteResult result,
            PendingMessage pending
    ) {
        UUID eventId = pending.samples().getFirst().eventId();
        return result.latestSamples().getOrDefault(eventId, pending.samples());
    }

    private void observeSequenceGaps(List<TelemetryBatchWriter.SequenceGap> sequenceGaps) {
        for (TelemetryBatchWriter.SequenceGap gap : sequenceGaps) {
            try {
                sequenceGapObserver.observe(gap);
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "遥测序列缺口指标记录失败，不阻塞已持久化事件: eventId={}, deviceId={}",
                        gap.eventId(), gap.deviceId(), exception
                );
            }
        }
    }

    private void observeQualityEvidence(List<QualityEvidence> evidence) {
        for (QualityEvidence item : evidence) {
            try {
                qualityEvidenceObserver.observe(item);
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "数据质量命中指标记录失败，不阻塞已持久化事件: eventId={}, rule={}",
                        item.eventId(), item.ruleCode(), exception
                );
            }
        }
    }

    private List<DecodedMessage> decodeValidMessages(List<Message> messages, Channel channel) {
        List<DecodedMessage> pending = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                DecodedTelemetry telemetry = decode(message);
                pending.add(new DecodedMessage(
                        message, telemetry.eventId(), telemetry.deviceId(), telemetry.payload()));
            } catch (InvalidTelemetryMessageException exception) {
                LOGGER.warn("拒绝不可重试的标准化遥测: {}", exception.getMessage());
                reject(message, channel);
            }
        }
        return List.copyOf(pending);
    }

    private List<PendingMessage> evaluateValidMessages(
            List<DecodedMessage> decoded,
            Channel channel
    ) {
        if (decoded.isEmpty()) {
            return List.of();
        }
        Map<UUID, DataQualityPolicy> policies;
        try {
            policies = qualityPolicySource.findByDeviceIds(
                    decoded.stream().map(DecodedMessage::deviceId).toList());
        } catch (RuntimeException exception) {
            LOGGER.error("本地数据质量策略暂时无法读取，{} 条消息将重新投递", decoded.size(), exception);
            decoded.forEach(item -> nack(item.message(), channel));
            return List.of();
        }
        List<PendingMessage> pending = new ArrayList<>(decoded.size());
        for (DecodedMessage item : decoded) {
            try {
                DataQualityPolicy policy = policies.get(item.deviceId());
                DataQualityEvaluation evaluation = policy == null
                        ? qualityEvaluator.evaluate(
                                item.eventId(), item.deviceId(), item.payload())
                        : qualityEvaluator.evaluate(
                                item.eventId(), item.deviceId(), item.payload(), policy);
                if (evaluation.samples().isEmpty()) {
                    throw new InvalidTelemetryMessageException("遥测消息不包含数值属性");
                }
                pending.add(new PendingMessage(item.message(), evaluation));
            } catch (IllegalArgumentException exception) {
                InvalidTelemetryMessageException invalid =
                        exception instanceof InvalidTelemetryMessageException candidate
                                ? candidate
                                : new InvalidTelemetryMessageException(
                                        "标准化遥测无法生成安全质量结果", exception);
                LOGGER.warn("拒绝不可重试的标准化遥测: {}", invalid.getMessage());
                reject(item.message(), channel);
            }
        }
        return List.copyOf(pending);
    }

    private RealtimeTelemetryEvent realtimeEvent(List<TelemetrySample> samples, Long ingestId) {
        if (ingestId == null) {
            throw new IllegalStateException("实时事件缺少已提交摄取序号");
        }
        TelemetrySample first = samples.getFirst();
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, Quality> qualities = new LinkedHashMap<>();
        Quality aggregateQuality = Quality.GOOD;
        for (TelemetrySample sample : samples) {
            values.put(sample.propertyId(), sample.value());
            qualities.put(sample.propertyId(), sample.quality());
            aggregateQuality = worse(aggregateQuality, sample.quality());
        }
        return new RealtimeTelemetryEvent(
                first.eventId(),
                first.deviceId(),
                ingestId,
                first.occurredAt(),
                first.receivedAt(),
                aggregateQuality,
                first.bootId(),
                first.sequence(),
                values,
                qualities
        );
    }

    private Quality worse(Quality left, Quality right) {
        if (left == Quality.BAD || right == Quality.BAD) {
            return Quality.BAD;
        }
        if (left == Quality.TIME_UNTRUSTED || right == Quality.TIME_UNTRUSTED) {
            return Quality.TIME_UNTRUSTED;
        }
        if (left == Quality.UNCERTAIN || right == Quality.UNCERTAIN) {
            return Quality.UNCERTAIN;
        }
        return Quality.GOOD;
    }

    private DecodedTelemetry decode(Message message) {
        try {
            MessageEnvelope<UnifiedDeviceMessage> envelope = jsonMapper.readValue(
                    message.getBody(), ENVELOPE_TYPE
            );
            validate(envelope);
            UnifiedDeviceMessage payload = envelope.payload();
            UUID deviceId = UUID.fromString(payload.deviceId());
            if (payload.properties().values().values().stream()
                    .noneMatch(Number.class::isInstance)) {
                throw new InvalidTelemetryMessageException("遥测消息不包含数值属性");
            }
            return new DecodedTelemetry(envelope.eventId(), deviceId, payload);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            if (exception instanceof InvalidTelemetryMessageException invalid) {
                throw invalid;
            }
            throw new InvalidTelemetryMessageException("标准化遥测格式非法", exception);
        }
    }

    private void validate(MessageEnvelope<UnifiedDeviceMessage> envelope) {
        UnifiedDeviceMessage payload = envelope.payload();
        if (!"telemetry.normalized".equals(envelope.eventType().value())) {
            throw new InvalidTelemetryMessageException("事件类型不是 telemetry.normalized");
        }
        if (envelope.schemaVersion() != 1) {
            throw new InvalidTelemetryMessageException("暂不支持该遥测结构版本");
        }
        if (payload.messageType() != MessageKind.TELEMETRY) {
            throw new InvalidTelemetryMessageException("载荷不是遥测消息");
        }
        if (!envelope.eventId().equals(payload.messageId())) {
            throw new InvalidTelemetryMessageException("信封事件标识与载荷消息标识不一致");
        }
        if (!envelope.partitionKey().equals(payload.deviceId())) {
            throw new InvalidTelemetryMessageException("分区键与设备标识不一致");
        }
    }

    private void ack(Message message, Channel channel) {
        try {
            channel.basicAck(deliveryTag(message), false);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        }
    }

    private void nack(Message message, Channel channel) {
        try {
            channel.basicNack(deliveryTag(message), false, true);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        }
    }

    private void reject(Message message, Channel channel) {
        try {
            channel.basicReject(deliveryTag(message), false);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        }
    }

    private long deliveryTag(Message message) {
        return message.getMessageProperties().getDeliveryTag();
    }

    /**
     * 表示消息结构或语义非法，重新投递也无法恢复。
     */
    public static final class InvalidTelemetryMessageException extends IllegalArgumentException {

        /**
         * 创建不可重试遥测异常。
         *
         * @param message 中文错误说明
         */
        public InvalidTelemetryMessageException(String message) {
            super(message);
        }

        /**
         * 创建带原始原因的不可重试遥测异常。
         *
         * @param message 中文错误说明
         * @param cause   原始解析异常
         */
        public InvalidTelemetryMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 发布已持久化实时遥测事件的边界。
     */
    @FunctionalInterface
    public interface RealtimePublisher {

        /**
         * 发布事件并等待 Broker 确认。
         *
         * @param event 已持久化实时遥测事件
         */
        void publish(RealtimeTelemetryEvent event);
    }

    private record DecodedTelemetry(
            UUID eventId,
            UUID deviceId,
            UnifiedDeviceMessage payload
    ) {
    }

    private record DecodedMessage(
            Message message,
            UUID eventId,
            UUID deviceId,
            UnifiedDeviceMessage payload
    ) {
    }

    private record PendingMessage(Message message, DataQualityEvaluation evaluation) {
        private List<TelemetrySample> samples() {
            return evaluation.samples();
        }

        private List<QualityEvidence> evidence() {
            return evaluation.evidence();
        }
    }
}
