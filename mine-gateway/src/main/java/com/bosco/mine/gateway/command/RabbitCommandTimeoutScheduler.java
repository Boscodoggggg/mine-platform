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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用 RabbitMQ 单消息 TTL 和死信路由持久化命令回复超时，不创建扫表任务。
 */
public final class RabbitCommandTimeoutScheduler implements CommandTimeoutScheduler {
    private final RabbitOperations rabbitOperations;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final long confirmTimeoutMillis;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建 RabbitMQ 命令超时调度器。
     *
     * @param rabbitOperations 平台 vhost 操作器
     * @param idGenerator 事件标识生成器
     * @param clock 时钟
     * @param confirmTimeoutMillis Broker 确认超时毫秒数
     */
    public RabbitCommandTimeoutScheduler(
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
     * 持久化一次性命令超时标记。
     *
     * @param commandId 命令标识
     * @param deviceId 设备标识
     * @param timeoutAt 回复截止时间
     * @param traceId 链路追踪标识
     * @param tenantScope 矿区范围
     */
    @Override
    public void schedule(
            UUID commandId,
            UUID deviceId,
            Instant timeoutAt,
            String traceId,
            String tenantScope
    ) {
        Instant now = clock.instant();
        long delayMillis = Duration.between(now, timeoutAt).toMillis();
        if (delayMillis <= 0) {
            throw new IllegalArgumentException("命令回复截止时间必须晚于当前时间");
        }
        CommandStatusPayload payload = new CommandStatusPayload(
                commandId, deviceId, "TIMEOUT", timeoutAt, null,
                Map.of(), "COMMAND_REPLY_TIMEOUT"
        );
        MessageEnvelope<CommandStatusPayload> envelope = new MessageEnvelope<>(
                idGenerator.next(), MessageType.of("command.status.changed"), 1,
                timeoutAt, now, "mine-gateway", traceId,
                commandId.toString(), deviceId.toString(), tenantScope, payload
        );
        Message message = MessageBuilder.withBody(write(envelope))
                .setContentType("application/json")
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(envelope.eventId().toString())
                .setExpiration(Long.toString(delayMillis))
                .setHeader("eventType", envelope.eventType().value())
                .setHeader("schemaVersion", envelope.schemaVersion())
                .build();
        rabbitOperations.invoke(scoped -> {
            scoped.send(MineTopology.COMMAND_EXCHANGE, "command.timeout.schedule", message);
            scoped.waitForConfirmsOrDie(confirmTimeoutMillis);
            return null;
        });
    }

    private byte[] write(MessageEnvelope<CommandStatusPayload> envelope) {
        try {
            return jsonMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("命令超时事件无法序列化", exception);
        }
    }
}
