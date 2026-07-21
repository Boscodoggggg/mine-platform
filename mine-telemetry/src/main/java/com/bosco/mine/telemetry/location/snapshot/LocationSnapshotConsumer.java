package com.bosco.mine.telemetry.location.snapshot;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.location.LocationRuntimeChangedPayload;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** 消费定位运行变更事件，成功激活或幂等确认后才 ACK。 */
public final class LocationSnapshotConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationSnapshotConsumer.class);
    private static final TypeReference<MessageEnvelope<LocationRuntimeChangedPayload>> TYPE =
            new TypeReference<>() { };

    private final LocationSnapshotLoader loader;
    private final LocationSnapshotMetrics metrics;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建定位运行快照消费者。
     *
     * @param loader 快照加载器
     * @param metrics 低基数结果指标
     */
    public LocationSnapshotConsumer(
            LocationSnapshotLoader loader,
            LocationSnapshotMetrics metrics
    ) {
        this.loader = Objects.requireNonNull(loader, "定位快照加载器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "定位快照指标不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public void onMessage(Message message, Channel channel) {
        Objects.requireNonNull(message, "定位快照消息不能为空");
        Objects.requireNonNull(channel, "RabbitMQ 通道不能为空");
        boolean decoded = false;
        try {
            DecodedEvent event = decode(message);
            decoded = true;
            loader.apply(event.envelope().eventId(), event.siteId(), event.envelope().payload());
        } catch (PermanentLocationSnapshotException exception) {
            metrics.complete(decoded
                    ? LocationSnapshotMetrics.Outcome.PERMANENT_FAILURE
                    : LocationSnapshotMetrics.Outcome.INVALID_MESSAGE);
            LOGGER.warn("拒绝不可重试的定位运行快照: {}", exception.getMessage());
            reject(message, channel);
            return;
        } catch (RuntimeException exception) {
            metrics.complete(LocationSnapshotMetrics.Outcome.TRANSIENT_FAILURE);
            LOGGER.error("定位运行快照暂时无法应用，消息将重新投递", exception);
            nack(message, channel);
            return;
        }
        metrics.complete(LocationSnapshotMetrics.Outcome.LOADED);
        ack(message, channel);
    }

    private DecodedEvent decode(Message message) {
        try {
            MessageEnvelope<LocationRuntimeChangedPayload> envelope =
                    jsonMapper.readValue(message.getBody(), TYPE);
            LocationRuntimeChangedPayload payload = envelope.payload();
            UUID siteId = UUID.fromString(envelope.partitionKey());
            if (!"location.runtime.changed".equals(envelope.eventType().value())
                    || envelope.schemaVersion() != 1
                    || !"mine-server".equals(envelope.producer())
                    || !payload.snapshotId().toString().equals(envelope.correlationId())) {
                throw new PermanentLocationSnapshotException(
                        "定位运行消息信封与载荷不一致");
            }
            return new DecodedEvent(envelope, siteId);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            if (exception instanceof PermanentLocationSnapshotException permanent) {
                throw permanent;
            }
            throw new PermanentLocationSnapshotException("定位运行消息格式非法", exception);
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

    private record DecodedEvent(
            MessageEnvelope<LocationRuntimeChangedPayload> envelope,
            UUID siteId
    ) {
    }
}
