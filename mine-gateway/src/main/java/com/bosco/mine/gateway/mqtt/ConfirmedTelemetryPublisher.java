package com.bosco.mine.gateway.mqtt;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 在专用平台 vhost 连接上发布标准化遥测，并同步等待 RabbitMQ publisher confirm。
 */
public final class ConfirmedTelemetryPublisher implements DeviceMessageConsumer.Publisher {

    private final RabbitOperations rabbitOperations;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper;

    /**
     * 创建带发布确认的遥测发布器。
     *
     * @param rabbitOperations 已连接到 {@code /mine-platform} 的操作模板
     * @param confirmTimeoutMillis 等待 Broker 确认的最大毫秒数
     */
    public ConfirmedTelemetryPublisher(RabbitOperations rabbitOperations, long confirmTimeoutMillis) {
        this.rabbitOperations = Objects.requireNonNull(rabbitOperations, "rabbitOperations 不能为空");
        if (confirmTimeoutMillis < 1) {
            throw new IllegalArgumentException("confirmTimeoutMillis 必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * 发布持久化 JSON 事件，并在同一专用通道等待 Broker 确认。
     *
     * @param envelope 标准化遥测事件
     */
    @Override
    public void publish(MessageEnvelope<UnifiedDeviceMessage> envelope) {
        Objects.requireNonNull(envelope, "envelope 不能为空");
        Message message = MessageBuilder.withBody(write(envelope))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(envelope.eventId().toString())
                .setHeader("eventType", envelope.eventType().value())
                .setHeader("schemaVersion", envelope.schemaVersion())
                .build();
        String routingKey = "telemetry.normalized.%s.%s".formatted(
                envelope.payload().productKey(),
                envelope.payload().deviceId()
        );
        rabbitOperations.invoke(scoped -> {
            scoped.send(MineTopology.TELEMETRY_EXCHANGE, routingKey, message);
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private byte[] write(MessageEnvelope<UnifiedDeviceMessage> envelope) {
        try {
            return jsonMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("标准化遥测事件序列化失败", exception);
        }
    }
}
