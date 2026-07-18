package com.bosco.mine.gateway.location;

import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.model.location.LocationObservation;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.util.Objects;

/**
 * 消费设备 vhost 的原始定位队列，并在内部观测获得 Broker 确认后手工 ACK。
 */
public final class LocationMessageConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationMessageConsumer.class);

    private final Normalizer normalizer;
    private final Publisher publisher;

    /**
     * 创建原始定位消费器。
     *
     * @param normalizer 可信身份与固定协议标准化边界
     * @param publisher 带发布确认的内部观测发布边界
     */
    public LocationMessageConsumer(Normalizer normalizer, Publisher publisher) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer 不能为空");
        this.publisher = Objects.requireNonNull(publisher, "publisher 不能为空");
    }

    /**
     * 标准化并可靠转发一条原始定位报文。
     *
     * @param message 原始定位消息
     * @param channel 设备 vhost 消费通道
     * @throws Exception RabbitMQ 手工确认操作失败时抛出
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            LocationObservation observation = normalizer.normalize(
                    message.getMessageProperties().getReceivedRoutingKey(),
                    message.getBody());
            publisher.publish(observation);
            channel.basicAck(deliveryTag, false);
        } catch (InvalidDeviceMessageException exception) {
            LOGGER.warn("拒绝不可重试的 MQTT 定位报文: {}", exception.getMessage());
            channel.basicReject(deliveryTag, false);
        } catch (RuntimeException exception) {
            LOGGER.error("内部定位观测发布失败，原始报文将重新投递", exception);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /** 原始定位报文标准化边界。 */
    @FunctionalInterface
    public interface Normalizer {
        /**
         * @param routingKey 原始路由键
         * @param body 原始报文字节
         * @return 可信定位观测
         */
        LocationObservation normalize(String routingKey, byte[] body);
    }

    /** 内部定位观测可靠发布边界。 */
    @FunctionalInterface
    public interface Publisher {
        /**
         * @param observation 待发布观测
         */
        void publish(LocationObservation observation);
    }
}
