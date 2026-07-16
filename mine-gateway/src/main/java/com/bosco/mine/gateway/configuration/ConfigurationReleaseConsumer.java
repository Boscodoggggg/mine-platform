package com.bosco.mine.gateway.configuration;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.configuration.ConfigReleasePayload;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

import java.util.Objects;

/**
 * 消费轻量配置事件，拉取、校验并原子激活完整快照后手工确认消息。
 */
public final class ConfigurationReleaseConsumer implements ChannelAwareMessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationReleaseConsumer.class);

    private final Decoder decoder;
    private final ConfigurationSnapshotClient client;
    private final ProtocolSnapshotRegistry registry;
    private final ConfigurationSnapshotStore snapshotStore;

    /**
     * 创建配置发布消费者。
     *
     * @param decoder 配置事件解码器
     * @param client 完整快照客户端
     * @param registry 协议快照注册表
     * @param snapshotStore 最后可用配置持久化边界
     */
    public ConfigurationReleaseConsumer(
            Decoder decoder,
            ConfigurationSnapshotClient client,
            ProtocolSnapshotRegistry registry,
            ConfigurationSnapshotStore snapshotStore
    ) {
        this.decoder = Objects.requireNonNull(decoder, "配置事件解码器不能为空");
        this.client = Objects.requireNonNull(client, "配置快照客户端不能为空");
        this.registry = Objects.requireNonNull(registry, "协议快照注册表不能为空");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "配置快照持久化边界不能为空");
    }

    /**
     * 处理配置事件；瞬时拉取失败重投，永久损坏消息进入死信。
     *
     * @param message RabbitMQ 配置消息
     * @param channel 平台消息消费通道
     * @throws Exception RabbitMQ 确认操作失败时抛出
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            MessageEnvelope<ConfigReleasePayload> envelope = decoder.decode(message.getBody());
            ConfigReleasePayload payload = envelope.payload();
            ConfigurationSnapshot snapshot = client.fetch(payload.releaseId());
            validateMatch(payload, snapshot);
            registry.activate(snapshot);
            snapshotStore.persist(snapshot);
            client.reportLoaded(payload.releaseId(), payload.contentHash());
            channel.basicAck(deliveryTag, false);
        } catch (InvalidConfigurationMessageException | InvalidConfigurationSnapshotException exception) {
            LOGGER.warn("拒绝不可重试的配置发布消息: {}", exception.getMessage());
            channel.basicReject(deliveryTag, false);
        } catch (ConfigurationFetchException exception) {
            LOGGER.warn("配置快照暂时无法拉取，消息将重新投递: {}", exception.getMessage());
            channel.basicNack(deliveryTag, false, true);
        } catch (RuntimeException exception) {
            LOGGER.error("配置发布处理出现瞬时失败，消息将重新投递", exception);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void validateMatch(ConfigReleasePayload payload, ConfigurationSnapshot snapshot) {
        if (!payload.releaseId().equals(snapshot.releaseId())
                || !payload.packageId().equals(snapshot.packageId())
                || !payload.productId().equals(snapshot.productId())
                || payload.releaseSequence() != snapshot.releaseSequence()
                || !payload.contentHash().equals(snapshot.contentHash())) {
            throw new InvalidConfigurationSnapshotException("配置事件与管理服务快照定位信息不一致");
        }
    }

    /** 配置发布信封解码边界。 */
    @FunctionalInterface
    public interface Decoder {
        /**
         * 解码配置发布消息。
         *
         * @param body 消息 JSON
         * @return 配置发布信封
         */
        MessageEnvelope<ConfigReleasePayload> decode(byte[] body);
    }
}
