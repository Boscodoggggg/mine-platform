package com.bosco.mine.gateway.mqtt;

import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.util.Objects;

/**
 * 消费设备 vhost 的 MQTT 上行消息，并在内部事件获得 Broker 确认后手工确认原始消息。
 */
public final class DeviceMessageConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceMessageConsumer.class);

    private final Normalizer normalizer;
    private final Publisher publisher;

    /**
     * 创建设备消息消费者。
     *
     * @param normalizer 标准化边界
     * @param publisher 带发布确认的内部事件发布边界
     */
    public DeviceMessageConsumer(Normalizer normalizer, Publisher publisher) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer 不能为空");
        this.publisher = Objects.requireNonNull(publisher, "publisher 不能为空");
    }

    /**
     * 处理一条 RabbitMQ 原始上行消息。
     *
     * @param message 原始消息
     * @param channel 设备 vhost 消费通道
     * @throws Exception RabbitMQ 确认操作失败时抛出
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            MessageEnvelope<UnifiedDeviceMessage> envelope = normalizer.normalize(
                    message.getMessageProperties().getReceivedRoutingKey(),
                    message.getBody()
            );
            publisher.publish(envelope);
            channel.basicAck(deliveryTag, false);
        } catch (InvalidDeviceMessageException exception) {
            LOGGER.warn("拒绝不可重试的 MQTT 设备报文: {}", exception.getMessage());
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException exception) {
            LOGGER.error("内部遥测事件发布失败，原始 MQTT 报文将重新投递", exception);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 原始报文标准化边界。
     */
    @FunctionalInterface
    public interface Normalizer {
        /**
         * 标准化设备消息。
         *
         * @param routingKey 原始 routing key
         * @param body 原始报文字节
         * @return 内部遥测事件
         */
        MessageEnvelope<UnifiedDeviceMessage> normalize(String routingKey, byte[] body);
    }

    /**
     * 内部遥测事件可靠发布边界。
     */
    @FunctionalInterface
    public interface Publisher {
        /**
         * 发布事件，只有 Broker 已确认接收时才能正常返回。
         *
         * @param envelope 内部遥测事件
         */
        void publish(MessageEnvelope<UnifiedDeviceMessage> envelope);
    }
}
