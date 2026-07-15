package com.bosco.mine.telemetry.consumer;

import com.bosco.mine.messaging.MessageEnvelope;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 批量消费 gateway 输出的标准化遥测，先更新 Redis 读模型，再事务写入 TimescaleDB 历史。
 */
public final class NormalizedTelemetryConsumer implements ChannelAwareBatchMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizedTelemetryConsumer.class);
    private static final TypeReference<MessageEnvelope<UnifiedDeviceMessage>> ENVELOPE_TYPE =
            new TypeReference<>() { };

    private final LatestValueRepository latestValues;
    private final TelemetryBatchWriter batchWriter;
    private final JsonMapper jsonMapper;

    /**
     * 创建标准化遥测批消费者。
     *
     * @param latestValues Redis 实时最新值仓储
     * @param batchWriter  TimescaleDB 事务批写器
     */
    public NormalizedTelemetryConsumer(
            LatestValueRepository latestValues,
            TelemetryBatchWriter batchWriter) {
        this.latestValues = Objects.requireNonNull(latestValues, "最新值仓储不能为空");
        this.batchWriter = Objects.requireNonNull(batchWriter, "历史批写器不能为空");
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
            pending.forEach(item -> latestValues.updateEventIfNewer(item.samples()));
            batchWriter.write(samples);
        } catch (RuntimeException exception) {
            LOGGER.error("遥测批次存储失败，{} 条 RabbitMQ 消息将重新投递", pending.size(), exception);
            pending.forEach(item -> nack(item.message(), channel));
            return;
        }
        pending.forEach(item -> ack(item.message(), channel));
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

    private record PendingMessage(Message message, List<TelemetrySample> samples) {
    }
}
