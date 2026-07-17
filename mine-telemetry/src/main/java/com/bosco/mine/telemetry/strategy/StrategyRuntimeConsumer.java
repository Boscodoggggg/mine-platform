package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
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

/**
 * 消费策略运行变更事件，成功原子切换后才确认 RabbitMQ 消息。
 */
public final class StrategyRuntimeConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrategyRuntimeConsumer.class);
    private static final TypeReference<MessageEnvelope<StrategyRuntimeChangedPayload>> ENVELOPE_TYPE =
            new TypeReference<>() { };

    private final StrategySnapshotLoader loader;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /** 创建策略运行变更消费者。 */
    public StrategyRuntimeConsumer(StrategySnapshotLoader loader) {
        this.loader = Objects.requireNonNull(loader, "策略快照加载器不能为空");
    }

    /**
     * 处理一条策略运行事件。
     *
     * <p>契约和完整性错误不可重试，进入死信；网络和存储暂时错误重新入队。</p>
     */
    @Override
    public void onMessage(Message message, Channel channel) {
        Objects.requireNonNull(message, "策略消息不能为空");
        Objects.requireNonNull(channel, "RabbitMQ 通道不能为空");
        try {
            MessageEnvelope<StrategyRuntimeChangedPayload> envelope = decode(message);
            loader.apply(envelope.eventId(), envelope.payload());
            ack(message, channel);
        } catch (PermanentStrategySnapshotException exception) {
            LOGGER.warn("拒绝不可重试的策略运行快照: {}", exception.getMessage());
            reject(message, channel);
        } catch (RuntimeException exception) {
            LOGGER.error("策略运行快照暂时无法应用，消息将重新投递", exception);
            nack(message, channel);
        }
    }

    private MessageEnvelope<StrategyRuntimeChangedPayload> decode(Message message) {
        try {
            MessageEnvelope<StrategyRuntimeChangedPayload> envelope =
                    jsonMapper.readValue(message.getBody(), ENVELOPE_TYPE);
            StrategyRuntimeChangedPayload payload = envelope.payload();
            if (!"strategy.runtime.changed".equals(envelope.eventType().value())
                    || envelope.schemaVersion() != 1
                    || !"mine-server".equals(envelope.producer())
                    || !payload.snapshotId().toString().equals(envelope.correlationId())
                    || !payload.productId().toString().equals(envelope.partitionKey())) {
                throw new PermanentStrategySnapshotException("策略运行消息信封与载荷不一致");
            }
            return envelope;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            if (exception instanceof PermanentStrategySnapshotException permanent) {
                throw permanent;
            }
            throw new PermanentStrategySnapshotException("策略运行消息格式非法", exception);
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
}
