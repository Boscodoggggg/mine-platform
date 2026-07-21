package com.bosco.mine.gateway.command;

import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
import com.bosco.mine.messaging.topology.MineTopology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 将平台命令发布到设备专属 MQTT 主题并等待设备 vhost Broker 确认。
 */
public final class MqttCommandPublisher implements CommandDispatchConsumer.DownlinkPublisher {
    private final RabbitOperations rabbitOperations;
    private final DeviceSessionRegistry registry;
    private final Clock clock;
    private final long confirmTimeoutMillis;
    private final CommandMessageCodec codec;

    /**
     * 创建设备 MQTT 命令发布器。
     *
     * @param rabbitOperations 设备 vhost 操作器
     * @param registry 可信设备身份注册表
     * @param clock 时钟
     * @param confirmTimeoutMillis Broker 确认超时毫秒数
     */
    public MqttCommandPublisher(
            RabbitOperations rabbitOperations,
            DeviceSessionRegistry registry,
            Clock clock,
            long confirmTimeoutMillis
    ) {
        this.rabbitOperations = Objects.requireNonNull(rabbitOperations, "RabbitMQ 操作器不能为空");
        this.registry = Objects.requireNonNull(registry, "设备身份注册表不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        if (confirmTimeoutMillis < 1) {
            throw new IllegalArgumentException("发布确认超时必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.codec = new CommandMessageCodec(registry, 64 * 1024);
    }

    /**
     * 发布设备命令并等待 Broker 确认。
     *
     * @param envelope 命令信封
     */
    @Override
    public void publish(MessageEnvelope<CommandDispatchPayload> envelope) {
        Objects.requireNonNull(envelope, "命令信封不能为空");
        CommandDispatchPayload payload = envelope.payload();
        DeviceSessionRegistry.DeviceIdentity identity = registry.findByDeviceId(payload.deviceId())
                .orElseThrow(() -> new InvalidCommandMessageException("目标设备身份未登记"));
        Instant now = clock.instant();
        long remainingMillis = Duration.between(now, payload.expiresAt()).toMillis();
        if (remainingMillis <= 0) {
            throw new InvalidCommandMessageException("命令已经过期");
        }
        MessageBuilder builder = MessageBuilder.withBody(codec.encodeDownlink(envelope));
        builder.setContentType("RAW_HEX".equals(payload.payloadFormat())
                ? "application/octet-stream" : "application/json");
        builder.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        builder.setMessageId(payload.commandId().toString());
        builder.setCorrelationId(envelope.traceId());
        builder.setExpiration(Long.toString(remainingMillis));
        if (!"RAW_HEX".equals(payload.payloadFormat())) {
            builder.setContentEncoding(StandardCharsets.UTF_8.name());
        }
        Message message = builder.build();
        String routingKey = "mine.v1.%s.%s.down.command".formatted(
                identity.productKey(), identity.clientId()
        );
        rabbitOperations.invoke(scoped -> {
            scoped.send(MineTopology.MQTT_EXCHANGE, routingKey, message);
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }
}
