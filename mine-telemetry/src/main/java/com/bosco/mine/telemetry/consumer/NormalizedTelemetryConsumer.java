package com.bosco.mine.telemetry.consumer;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
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
import java.time.Instant;
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
        this.latestValues = Objects.requireNonNull(latestValues, "最新值仓储不能为空");
        this.batchWriter = Objects.requireNonNull(batchWriter, "历史批写器不能为空");
        this.realtimePublisher = Objects.requireNonNull(realtimePublisher, "实时事件发布器不能为空");
        this.sequenceGapObserver = Objects.requireNonNull(
                sequenceGapObserver, "序列缺口观察器不能为空"
        );
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
        List<PendingMessage> pending = decodeValidMessages(messages, channel);
        if (pending.isEmpty()) {
            return;
        }

        List<TelemetrySample> samples = pending.stream()
                .flatMap(item -> item.samples().stream())
                .toList();
        try {
            TelemetryBatchWriter.WriteResult result = batchWriter.write(samples);
            observeSequenceGaps(result.sequenceGaps());
            pending.stream()
                    .filter(item -> result.latestEventIds().contains(
                            item.samples().getFirst().eventId()
                    ))
                    .forEach(item -> latestValues.updateEventIfNewer(item.samples()));
            pending.stream()
                    .filter(item -> result.latestEventIds().contains(
                            item.samples().getFirst().eventId()
                    ))
                    .map(item -> realtimeEvent(
                            item.samples(),
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

    private List<PendingMessage> decodeValidMessages(List<Message> messages, Channel channel) {
        List<PendingMessage> pending = new ArrayList<>(messages.size());
        for (Message message : messages) {
            try {
                pending.add(new PendingMessage(message, decode(message)));
            } catch (InvalidTelemetryMessageException exception) {
                LOGGER.warn("拒绝不可重试的标准化遥测: {}", exception.getMessage());
                reject(message, channel);
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
        samples.forEach(sample -> values.put(sample.propertyId(), sample.value()));
        return new RealtimeTelemetryEvent(
                first.eventId(),
                first.deviceId(),
                ingestId,
                first.occurredAt(),
                first.receivedAt(),
                first.quality(),
                first.bootId(),
                first.sequence(),
                values
        );
    }

    private List<TelemetrySample> decode(Message message) {
        try {
            MessageEnvelope<UnifiedDeviceMessage> envelope = jsonMapper.readValue(
                    message.getBody(), ENVELOPE_TYPE
            );
            validate(envelope);
            UnifiedDeviceMessage payload = envelope.payload();
            UUID deviceId = UUID.fromString(payload.deviceId());
            Instant effectiveAt = payload.quality() == Quality.TIME_UNTRUSTED
                    ? payload.receivedAt()
                    : payload.occurredAt();
            List<TelemetrySample> samples = payload.properties().values().entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof Number)
                    .map(entry -> new TelemetrySample(
                            envelope.eventId(),
                            deviceId,
                            payload.productKey(),
                            entry.getKey(),
                            ((Number) entry.getValue()).doubleValue(),
                            payload.quality(),
                            payload.bootId(),
                            payload.sequence(),
                            payload.messageType(),
                            payload.occurredAt(),
                            payload.receivedAt(),
                            effectiveAt
                    ))
                    .toList();
            if (samples.isEmpty()) {
                throw new InvalidTelemetryMessageException("遥测消息不包含数值属性");
            }
            return samples;
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

    private record PendingMessage(Message message, List<TelemetrySample> samples) {
    }
}
