package com.bosco.mine.telemetry.location;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.model.location.LocationObservation;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 消费可信定位观测，事务提交后先 ACK，再以可恢复方式刷新 Redis 和唤醒 Outbox。
 */
public final class LocationObservationConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LocationObservationConsumer.class);

    private final LocationFactWriter writer;
    private final LocationLatestCache latestCache;
    private final CommittedEventObserver outboxObserver;
    private final LocationPipelineMetrics metrics;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建单条手工确认定位消费者。
     *
     * @param writer 位置事实事务写入器
     * @param latestCache Redis 当前位置加速层
     * @param outboxObserver 已提交实时 Outbox 唤醒器
     */
    public LocationObservationConsumer(
            LocationFactWriter writer,
            LocationLatestCache latestCache,
            CommittedEventObserver outboxObserver
    ) {
        this(writer, latestCache, outboxObserver, LocationPipelineMetrics.noop());
    }

    /**
     * 创建带固定处理结果指标的定位消费者。
     *
     * @param writer 位置事实事务写入器
     * @param latestCache Redis 当前位置加速层
     * @param outboxObserver 已提交实时 Outbox 唤醒器
     * @param metrics 定位流水线指标
     */
    public LocationObservationConsumer(
            LocationFactWriter writer,
            LocationLatestCache latestCache,
            CommittedEventObserver outboxObserver,
            LocationPipelineMetrics metrics
    ) {
        this.writer = Objects.requireNonNull(writer, "定位事实写入器不能为空");
        this.latestCache = Objects.requireNonNull(latestCache, "定位最新缓存不能为空");
        this.outboxObserver = Objects.requireNonNull(
                outboxObserver, "定位 Outbox 观察器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "定位流水线指标不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public void onMessage(Message message, Channel channel) {
        Objects.requireNonNull(message, "定位观测消息不能为空");
        Objects.requireNonNull(channel, "RabbitMQ 通道不能为空");
        LocationFactWriter.WriteResult result;
        try {
            result = writer.write(decode(message));
            recordWriteResult(result);
        } catch (InvalidObservationMessageException exception) {
            if (exception.candidate() != null) {
                try {
                    recordWriteResult(writer.quarantineInvalid(exception.candidate()));
                } catch (LocationFactWriter.PermanentLocationObservationException conflict) {
                    metrics.ingest(LocationPipelineMetrics.IngestResult.PERMANENT_FAILURE,
                            LocationPipelineMetrics.IngestReason.IDEMPOTENCY_CONFLICT);
                    LOGGER.warn("拒绝存在幂等冲突的非法定位观测: {}", conflict.getMessage());
                    reject(message, channel);
                    return;
                } catch (RuntimeException storageException) {
                    metrics.ingest(LocationPipelineMetrics.IngestResult.TRANSIENT_FAILURE,
                            LocationPipelineMetrics.IngestReason.STORAGE_FAILURE);
                    LOGGER.error("非法定位观测暂时无法写入隔离区，消息将重新投递",
                            storageException);
                    nack(message, channel);
                    return;
                }
            } else {
                metrics.ingest(LocationPipelineMetrics.IngestResult.INVALID_MESSAGE,
                        LocationPipelineMetrics.IngestReason.INVALID_MESSAGE);
            }
            LOGGER.warn("拒绝不可重试的定位观测: {}", exception.getMessage());
            reject(message, channel);
            return;
        } catch (LocationFactWriter.PermanentLocationObservationException exception) {
            metrics.ingest(LocationPipelineMetrics.IngestResult.PERMANENT_FAILURE,
                    LocationPipelineMetrics.IngestReason.IDEMPOTENCY_CONFLICT);
            LOGGER.warn("拒绝不可重试的定位观测: {}", exception.getMessage());
            reject(message, channel);
            return;
        } catch (RuntimeException exception) {
            metrics.ingest(LocationPipelineMetrics.IngestResult.TRANSIENT_FAILURE,
                    LocationPipelineMetrics.IngestReason.STORAGE_FAILURE);
            LOGGER.error("定位观测暂时无法持久化，消息将重新投递", exception);
            nack(message, channel);
            return;
        }
        if (result.outcome() == LocationFactWriter.Outcome.QUARANTINED) {
            reject(message, channel);
            return;
        }

        ack(message, channel);
        try {
            latestCache.updateIfNewer(result.event());
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis 当前位置刷新失败，将由事实重建任务恢复: eventId={}",
                    result.event().eventId(), exception);
        }
        try {
            outboxObserver.observe(result.event().eventId());
        } catch (RuntimeException exception) {
            LOGGER.warn("定位实时 Outbox 精确唤醒失败，将由恢复扫描接管: eventId={}",
                    result.event().eventId(), exception);
        }
    }

    private void recordWriteResult(LocationFactWriter.WriteResult result) {
        LocationPipelineMetrics.IngestResult ingestResult = switch (result.outcome()) {
            case STORED -> LocationPipelineMetrics.IngestResult.STORED;
            case DUPLICATE -> LocationPipelineMetrics.IngestResult.DUPLICATE;
            case QUARANTINED -> LocationPipelineMetrics.IngestResult.QUARANTINED;
        };
        LocationPipelineMetrics.IngestReason reason = result.quarantineReason() == null
                ? (result.outcome() == LocationFactWriter.Outcome.QUARANTINED
                ? LocationPipelineMetrics.IngestReason.UNKNOWN
                : LocationPipelineMetrics.IngestReason.NONE)
                : LocationPipelineMetrics.IngestReason.valueOf(
                        result.quarantineReason().name());
        metrics.ingest(ingestResult, reason);
    }

    private LocationObservation decode(Message message) {
        JsonNode content = null;
        try {
            Object eventType = message.getMessageProperties().getHeaders().get("eventType");
            Object schemaVersion = message.getMessageProperties().getHeaders().get("schemaVersion");
            if (!MineTopology.LOCATION_OBSERVATION_ROUTING_KEY.equals(eventType)
                    || !(schemaVersion instanceof Number number)
                    || number.intValue() != 1) {
                throw new InvalidObservationMessageException("定位观测消息头不合法");
            }
            content = jsonMapper.readTree(message.getBody());
            if (content == null || !content.isObject()) {
                throw new InvalidObservationMessageException("定位观测正文必须是对象");
            }
            LocationObservation observation = jsonMapper.treeToValue(
                    content, LocationObservation.class);
            String messageId = message.getMessageProperties().getMessageId();
            if (messageId == null
                    || !observation.observationId().equals(UUID.fromString(messageId))) {
                throw new InvalidObservationMessageException(
                        "定位观测消息标识与载荷不一致",
                        candidate(content, message.getBody()), null);
            }
            return observation;
        } catch (InvalidObservationMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidObservationMessageException(
                    "定位观测格式非法", candidate(content, message.getBody()), exception);
        }
    }

    private LocationFactWriter.InvalidObservationCandidate candidate(
            JsonNode content,
            byte[] rawBody
    ) {
        if (content == null || !content.isObject()) {
            return null;
        }
        try {
            JsonNode sequence = content.get("sequence");
            if (sequence == null || !sequence.isIntegralNumber()) {
                return null;
            }
            return new LocationFactWriter.InvalidObservationCandidate(
                    UUID.fromString(requiredText(content, "observationId")),
                    UUID.fromString(requiredText(content, "sourceDeviceId")),
                    requiredText(content, "bootId"), sequence.longValue(),
                    Instant.parse(requiredText(content, "occurredAt")),
                    Instant.parse(requiredText(content, "receivedAt")), rawBody);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String requiredText(JsonNode content, String field) {
        JsonNode value = content.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("非法定位观测缺少必要身份字段");
        }
        return value.asString();
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

    /** 在提交后精确唤醒一个位置实时 Outbox 事件。 */
    @FunctionalInterface
    public interface CommittedEventObserver {
        /** @param eventId 已提交的原始定位观测标识 */
        void observe(UUID eventId);
    }

    private static final class InvalidObservationMessageException extends RuntimeException {
        private final LocationFactWriter.InvalidObservationCandidate candidate;

        private InvalidObservationMessageException(String message) {
            this(message, null, null);
        }

        private InvalidObservationMessageException(
                String message,
                LocationFactWriter.InvalidObservationCandidate candidate,
                Throwable cause
        ) {
            super(message, cause);
            this.candidate = candidate;
        }

        private LocationFactWriter.InvalidObservationCandidate candidate() {
            return candidate;
        }
    }
}
