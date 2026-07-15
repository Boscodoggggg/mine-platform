package com.bosco.mine.gateway.command;

import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 消费设备业务 ACK，并在平台状态事件获得确认后确认原始设备消息。
 */
public final class CommandAckConsumer implements ChannelAwareMessageListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandAckConsumer.class);

    private final Decoder decoder;
    private final CommandStatusEventPublisher statusPublisher;

    /**
     * 创建命令 ACK 消费者。
     *
     * @param decoder ACK 解码器
     * @param statusPublisher 状态事件发布器
     */
    public CommandAckConsumer(Decoder decoder, CommandStatusEventPublisher statusPublisher) {
        this.decoder = Objects.requireNonNull(decoder, "ACK 解码器不能为空");
        this.statusPublisher = Objects.requireNonNull(statusPublisher, "状态发布器不能为空");
    }

    /**
     * 处理一条设备业务 ACK。
     *
     * @param message 原始 ACK 消息
     * @param channel 设备 vhost 消费通道
     * @throws Exception RabbitMQ 确认失败时抛出
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            DecodedAck ack = decoder.decode(
                    message.getMessageProperties().getReceivedRoutingKey(), message.getBody()
            );
            statusPublisher.publish(new CommandStatusPayload(
                    ack.commandId(), ack.deviceId(), ack.status(), ack.occurredAt(),
                    ack.occurredAt(), ack.reply(), ack.errorCode()
            ), ack.traceId(), ack.tenantScope());
            channel.basicAck(deliveryTag, false);
        } catch (InvalidCommandMessageException exception) {
            LOGGER.warn("拒绝不可重试的设备命令 ACK: {}", exception.getMessage());
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException exception) {
            LOGGER.error("命令 ACK 状态事件未确认，设备消息将重新投递", exception);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 设备 ACK 解码边界。
     */
    @FunctionalInterface
    public interface Decoder {
        /**
         * 解码并校验设备 ACK。
         *
         * @param routingKey MQTT 转换后的路由键
         * @param body ACK JSON 字节
         * @return 已绑定可信设备身份的 ACK
         */
        DecodedAck decode(String routingKey, byte[] body);
    }

    /**
     * 已绑定可信设备身份的命令 ACK。
     *
     * @param commandId 命令标识
     * @param deviceId 设备标识
     * @param status 设备终态
     * @param occurredAt 设备回复时间
     * @param reply 结构化回复
     * @param errorCode 错误码，可为空
     * @param traceId 链路标识
     * @param tenantScope 矿区范围
     */
    public record DecodedAck(
            UUID commandId,
            UUID deviceId,
            String status,
            Instant occurredAt,
            Map<String, Object> reply,
            String errorCode,
            String traceId,
            String tenantScope
    ) {
        /**
         * 创建并校验已解码 ACK。
         */
        public DecodedAck {
            Objects.requireNonNull(commandId, "命令标识不能为空");
            Objects.requireNonNull(deviceId, "设备标识不能为空");
            Objects.requireNonNull(status, "命令状态不能为空");
            Objects.requireNonNull(occurredAt, "ACK 时间不能为空");
            reply = reply == null ? Map.of() : Map.copyOf(reply);
            if (traceId == null || traceId.isBlank() || tenantScope == null || tenantScope.isBlank()) {
                throw new IllegalArgumentException("ACK 链路标识和矿区范围不能为空");
            }
        }
    }
}
