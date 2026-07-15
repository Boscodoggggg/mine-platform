package com.bosco.mine.server.modules.command.status;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * 消费 gateway 命令状态事件，在数据库事务提交后手工确认 RabbitMQ 消息。
 */
public final class CommandStatusConsumer implements ChannelAwareMessageListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandStatusConsumer.class);
    private static final String EVENT_TYPE = "command.status.changed";

    private final CommandStatusService service;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建命令状态消费者。
     *
     * @param service 命令状态服务
     */
    public CommandStatusConsumer(CommandStatusService service) {
        this.service = Objects.requireNonNull(service, "命令状态服务不能为空");
    }

    /**
     * 处理一条命令状态事件。
     *
     * @param message RabbitMQ 状态消息
     * @param channel 消费通道
     * @throws Exception RabbitMQ 确认失败时抛出
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            MessageEnvelope<CommandStatusPayload> envelope = decode(message.getBody());
            service.apply(envelope.eventId(), envelope.payload());
            channel.basicAck(deliveryTag, false);
        } catch (InvalidCommandStatusMessageException exception) {
            LOGGER.warn("拒绝不可重试的命令状态事件: {}", exception.getMessage());
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException exception) {
            LOGGER.error("命令状态事务未完成，消息将重新投递", exception);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private MessageEnvelope<CommandStatusPayload> decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > 64 * 1024) {
            throw new InvalidCommandStatusMessageException("命令状态消息大小不合法");
        }
        try {
            MessageEnvelope<CommandStatusPayload> envelope = jsonMapper.readValue(
                    body, new TypeReference<>() { }
            );
            if (!EVENT_TYPE.equals(envelope.eventType().value()) || envelope.schemaVersion() != 1) {
                throw new InvalidCommandStatusMessageException("命令状态事件类型或结构版本不受支持");
            }
            if (!envelope.payload().commandId().toString().equals(envelope.correlationId())
                    || !envelope.payload().deviceId().toString().equals(envelope.partitionKey())) {
                throw new InvalidCommandStatusMessageException("命令状态信封关联标识与载荷不一致");
            }
            return envelope;
        } catch (InvalidCommandStatusMessageException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidCommandStatusMessageException("命令状态 JSON 无法解析", exception);
        }
    }
}
