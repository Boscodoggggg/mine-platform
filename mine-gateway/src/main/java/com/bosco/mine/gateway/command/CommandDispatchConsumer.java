package com.bosco.mine.gateway.command;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.command.CommandDispatchPayload;
import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 消费平台命令，在设备侧 Broker 确认接收后发布 SENT 状态并确认原消息。
 */
public final class CommandDispatchConsumer implements ChannelAwareMessageListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandDispatchConsumer.class);

    private final Decoder decoder;
    private final DownlinkPublisher downlinkPublisher;
    private final CommandStatusEventPublisher statusPublisher;
    private final CommandTimeoutScheduler timeoutScheduler;
    private final Clock clock;

    /**
     * 创建命令下行消费者。
     *
     * @param decoder 命令消息解码器
     * @param downlinkPublisher 设备下行发布器
     * @param statusPublisher 状态事件发布器
     * @param timeoutScheduler 回复超时调度器
     * @param clock 时钟
     */
    public CommandDispatchConsumer(
            Decoder decoder,
            DownlinkPublisher downlinkPublisher,
            CommandStatusEventPublisher statusPublisher,
            CommandTimeoutScheduler timeoutScheduler,
            Clock clock
    ) {
        this.decoder = Objects.requireNonNull(decoder, "命令解码器不能为空");
        this.downlinkPublisher = Objects.requireNonNull(downlinkPublisher, "下行发布器不能为空");
        this.statusPublisher = Objects.requireNonNull(statusPublisher, "状态发布器不能为空");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "超时调度器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /**
     * 处理一条平台命令消息。
     *
     * @param message RabbitMQ 命令消息
     * @param channel 平台消息消费通道
     * @throws Exception RabbitMQ 确认失败时抛出
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            MessageEnvelope<CommandDispatchPayload> envelope = decoder.decode(message.getBody());
            downlinkPublisher.publish(envelope);
            Instant sentAt = clock.instant();
            statusPublisher.publish(new CommandStatusPayload(
                    envelope.payload().commandId(), envelope.payload().deviceId(), "SENT",
                    sentAt, null, Map.of(), null
            ), envelope.traceId(), envelope.tenantScope());
            timeoutScheduler.schedule(
                    envelope.payload().commandId(), envelope.payload().deviceId(),
                    sentAt.plusSeconds(envelope.payload().timeoutSeconds()),
                    envelope.traceId(), envelope.tenantScope()
            );
            channel.basicAck(deliveryTag, false);
        } catch (InvalidCommandMessageException exception) {
            LOGGER.warn("拒绝不可重试的命令下行消息: {}", exception.getMessage());
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException exception) {
            LOGGER.error("命令下行或状态发布未确认，平台消息将重新投递", exception);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 平台命令信封解码边界。
     */
    @FunctionalInterface
    public interface Decoder {
        /**
         * 解码并校验命令信封。
         *
         * @param body JSON 字节
         * @return 命令信封
         */
        MessageEnvelope<CommandDispatchPayload> decode(byte[] body);
    }

    /**
     * 设备下行可靠发布边界。
     */
    @FunctionalInterface
    public interface DownlinkPublisher {
        /**
         * 发布设备命令，只有设备 vhost Broker 确认后正常返回。
         *
         * @param envelope 命令信封
         */
        void publish(MessageEnvelope<CommandDispatchPayload> envelope);
    }
}
