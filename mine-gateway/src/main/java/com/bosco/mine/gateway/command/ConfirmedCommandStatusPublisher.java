package com.bosco.mine.gateway.command;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.bosco.mine.messaging.topology.MineTopology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

/**
 * 在平台 vhost 发布持久化命令状态事件并等待 Broker 确认。
 */
public final class ConfirmedCommandStatusPublisher implements CommandStatusEventPublisher {
    private final RabbitOperations rabbitOperations;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建命令状态发布器。
     *
     * @param rabbitOperations 平台 vhost 操作器
     * @param idGenerator 事件标识生成器
     * @param clock 时钟
     * @param confirmTimeoutMillis Broker 确认超时毫秒数
     */
    public ConfirmedCommandStatusPublisher(
            RabbitOperations rabbitOperations,
            IdGenerator idGenerator,
            Clock clock,
            long confirmTimeoutMillis
    ) {
        this.rabbitOperations = Objects.requireNonNull(rabbitOperations, "RabbitMQ 操作器不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "事件标识生成器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        if (confirmTimeoutMillis < 1) {
            throw new IllegalArgumentException("发布确认超时必须大于 0");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /**
     * 发布统一信封格式的命令状态事件。
     *
     * @param payload 命令状态载荷
     * @param traceId 链路追踪标识
     * @param tenantScope 矿区隔离范围
     */
    @Override
    public void publish(CommandStatusPayload payload, String traceId, String tenantScope) {
        MessageEnvelope<CommandStatusPayload> envelope = new MessageEnvelope<>(
                idGenerator.next(), MessageType.of("command.status.changed"), 1,
                payload.occurredAt(), clock.instant(), "mine-gateway", traceId,
                payload.commandId().toString(), payload.deviceId().toString(), tenantScope, payload
        );
        Message message = MessageBuilder.withBody(write(envelope))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(envelope.eventId().toString())
                .setHeader("eventType", envelope.eventType().value())
                .setHeader("schemaVersion", envelope.schemaVersion())
                .build();
        String routingKey = "command.status." + payload.status().toLowerCase(Locale.ROOT);
        rabbitOperations.invoke(scoped -> {
            scoped.send(MineTopology.COMMAND_EXCHANGE, routingKey, message);
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private byte[] write(MessageEnvelope<CommandStatusPayload> envelope) {
        try {
            return jsonMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("命令状态事件无法序列化", exception);
        }
    }
}
