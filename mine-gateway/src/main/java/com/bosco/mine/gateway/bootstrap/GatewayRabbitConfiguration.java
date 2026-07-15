package com.bosco.mine.gateway.bootstrap;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.mqtt.ConfirmedTelemetryPublisher;
import com.bosco.mine.gateway.mqtt.DeviceMessageConsumer;
import com.bosco.mine.gateway.normalize.DeviceMessageNormalizer;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.gateway.session.DeviceIdentitySnapshotLoader;
import com.bosco.mine.messaging.topology.MineTopology;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;

/**
 * 装配 gateway 的设备消费连接和平台发布连接。
 *
 * <p>两套连接固定进入不同 vhost 并使用不同最小权限账号，避免设备原始报文入口与平台事件权限混用。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayRabbitProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "mine.gateway.rabbit", name = "enabled", havingValue = "true")
public class GatewayRabbitConfiguration {

    /**
     * 创建访问设备 vhost 的只读连接。
     *
     * @param properties gateway RabbitMQ 参数
     * @return 设备消费连接工厂
     */
    @Bean(destroyMethod = "destroy")
    public CachingConnectionFactory deviceConnectionFactory(GatewayRabbitProperties properties) {
        return connectionFactory(properties, properties.device(), MineTopology.DEVICE_VHOST, false);
    }

    /**
     * 创建访问平台 vhost 的只写连接并启用简单 publisher confirm。
     *
     * @param properties gateway RabbitMQ 参数
     * @return 平台发布连接工厂
     */
    @Bean(destroyMethod = "destroy")
    public CachingConnectionFactory platformConnectionFactory(GatewayRabbitProperties properties) {
        return connectionFactory(properties, properties.platform(), MineTopology.PLATFORM_VHOST, true);
    }

    /**
     * 创建设备身份快照容器。初始为空，未收到可信配置快照前所有设备消息均拒绝。
     *
     * @return 设备身份注册表
     */
    @Bean
    public DeviceSessionRegistry deviceSessionRegistry() {
        return new DeviceSessionRegistry();
    }

    /**
     * 创建身份快照加载器并在设备消费启动前完成首次加载。
     *
     * @param registry 运行时身份注册表
     * @param properties gateway RabbitMQ 参数
     * @return 支持定时热刷新的快照加载器
     */
    @Bean
    public DeviceIdentitySnapshotLoader deviceIdentitySnapshotLoader(
            DeviceSessionRegistry registry,
            GatewayRabbitProperties properties
    ) {
        DeviceIdentitySnapshotLoader loader = new DeviceIdentitySnapshotLoader(
                registry,
                properties.resolvedIdentitySnapshotFile()
        );
        loader.reload();
        return loader;
    }

    /**
     * 创建首个 MQTT JSON 报文标准化器。
     *
     * @param registry 可信设备身份快照
     * @param properties gateway RabbitMQ 参数
     * @return 设备报文标准化器
     */
    @Bean
    public DeviceMessageNormalizer deviceMessageNormalizer(
            DeviceSessionRegistry registry,
            GatewayRabbitProperties properties
    ) {
        return new DeviceMessageNormalizer(
                registry,
                IdGenerator.timeOrdered(),
                Clock.systemUTC(),
                properties.resolvedMaxPayloadBytes()
        );
    }

    /**
     * 创建使用平台连接的可靠遥测发布器。
     *
     * @param platformConnectionFactory 平台 vhost 连接
     * @param properties gateway RabbitMQ 参数
     * @return 带 Broker 确认的发布器
     */
    @Bean
    public ConfirmedTelemetryPublisher confirmedTelemetryPublisher(
            CachingConnectionFactory platformConnectionFactory,
            GatewayRabbitProperties properties
    ) {
        return new ConfirmedTelemetryPublisher(
                new RabbitTemplate(platformConnectionFactory),
                properties.resolvedConfirmTimeoutMillis()
        );
    }

    /**
     * 创建设备原始消息消费处理器。
     *
     * @param normalizer 标准化器
     * @param publisher 可靠发布器
     * @return 原始设备消息处理器
     */
    @Bean
    public DeviceMessageConsumer deviceMessageConsumer(
            DeviceMessageNormalizer normalizer,
            ConfirmedTelemetryPublisher publisher
    ) {
        return new DeviceMessageConsumer(normalizer, publisher);
    }

    /**
     * 创建手工确认的设备上行监听容器。
     *
     * @param deviceConnectionFactory 设备 vhost 连接
     * @param consumer 消息处理器
     * @param properties gateway RabbitMQ 参数
     * @param snapshotLoader 身份快照加载器，用于保证首次快照先于消费启动
     * @return 上行消息监听容器
     */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer deviceMessageListenerContainer(
            CachingConnectionFactory deviceConnectionFactory,
            DeviceMessageConsumer consumer,
            GatewayRabbitProperties properties,
            DeviceIdentitySnapshotLoader snapshotLoader
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(deviceConnectionFactory);
        container.setQueueNames(MineTopology.DEVICE_UPLINK_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(positive(properties.resolvedPrefetch(), "prefetch"));
        container.setDefaultRequeueRejected(false);
        container.setAutoStartup(properties.resolvedAutoStartup());
        container.setMessageListener(consumer);
        return container;
    }

    private CachingConnectionFactory connectionFactory(
            GatewayRabbitProperties properties,
            GatewayRabbitProperties.Account account,
            String virtualHost,
            boolean publisherConfirms
    ) {
        if (account == null || account.username() == null || account.username().isBlank()) {
            throw new IllegalArgumentException(virtualHost + " 服务账号不能为空");
        }
        if (account.passwordFile() == null) {
            throw new IllegalArgumentException(virtualHost + " 密码文件不能为空");
        }
        CachingConnectionFactory factory = new CachingConnectionFactory(
                properties.resolvedHost(), positive(properties.resolvedPort(), "port")
        );
        factory.setVirtualHost(virtualHost);
        factory.setUsername(account.username());
        factory.setPassword(readSecret(account.passwordFile(), virtualHost + " 密码"));
        if (publisherConfirms) {
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        }
        return factory;
    }

    private String readSecret(java.nio.file.Path path, String name) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) throw new IllegalArgumentException(name + "文件不能为空");
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取" + name + "文件: " + path, exception);
        }
    }

    private int positive(int value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " 必须大于 0");
        return value;
    }
}
