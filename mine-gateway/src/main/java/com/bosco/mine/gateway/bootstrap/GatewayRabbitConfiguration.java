package com.bosco.mine.gateway.bootstrap;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.command.CommandAckConsumer;
import com.bosco.mine.gateway.command.CommandDispatchConsumer;
import com.bosco.mine.gateway.command.CommandMessageCodec;
import com.bosco.mine.gateway.command.ConfirmedCommandStatusPublisher;
import com.bosco.mine.gateway.command.MqttCommandPublisher;
import com.bosco.mine.gateway.command.RabbitCommandTimeoutScheduler;
import com.bosco.mine.gateway.configuration.ConfigurationReleaseCodec;
import com.bosco.mine.gateway.configuration.ConfigurationReleaseConsumer;
import com.bosco.mine.gateway.configuration.ConfigurationDistributionMetrics;
import com.bosco.mine.gateway.configuration.ConfigurationSnapshotCache;
import com.bosco.mine.gateway.configuration.GatewayConfigurationDistributionProperties;
import com.bosco.mine.gateway.configuration.HttpConfigurationSnapshotClient;
import com.bosco.mine.gateway.configuration.ProtocolSnapshotRegistry;
import com.bosco.mine.gateway.location.ConfirmedLocationPublisher;
import com.bosco.mine.gateway.location.LocationMessageConsumer;
import com.bosco.mine.gateway.location.LocationMessageNormalizer;
import com.bosco.mine.gateway.mqtt.ConfirmedTelemetryPublisher;
import com.bosco.mine.gateway.mqtt.DeviceMessageConsumer;
import com.bosco.mine.gateway.normalize.DeviceMessageNormalizer;
import com.bosco.mine.gateway.session.DeviceIdentitySnapshotLoader;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.topology.MineTopology;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;

/**
 * 装配 gateway 的设备域连接和平台内部连接。
 *
 * <p>两套连接固定进入不同 vhost 并使用不同最小权限账号。设备域连接负责上行消费和 MQTT 下行，平台连接负责
 * 命令消费以及遥测、定位观测和命令状态发布，两个安全域的权限不得混用。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        GatewayRabbitProperties.class,
        GatewayConfigurationDistributionProperties.class
})
@EnableScheduling
@ConditionalOnProperty(prefix = "mine.gateway.rabbit", name = "enabled", havingValue = "true")
public class GatewayRabbitConfiguration {

    /**
     * 创建访问设备 vhost 的连接，并为 MQTT 命令下行启用发布确认。
     *
     * @param properties gateway RabbitMQ 参数
     * @return 设备消费连接工厂
     */
    @Bean(destroyMethod = "destroy")
    public CachingConnectionFactory deviceConnectionFactory(GatewayRabbitProperties properties) {
        return connectionFactory(properties, properties.device(), MineTopology.DEVICE_VHOST, true);
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
     * 打开 Gateway 最后可用配置的有界本地缓存。
     *
     * @param properties 配置分发参数
     * @return 配置快照缓存
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.gateway.configuration-distribution",
            name = "enabled",
            havingValue = "true")
    public ConfigurationSnapshotCache configurationSnapshotCache(
            GatewayConfigurationDistributionProperties properties
    ) {
        return new ConfigurationSnapshotCache(
                properties.resolvedCacheFile(),
                properties.resolvedMaxCacheBytes()
        );
    }

    /**
     * 创建动态协议快照注册表；分发启用时缺失产品配置将阻止设备报文解析。
     *
     * @param properties 配置分发参数
     * @param snapshotCacheProvider 最后可用配置本地缓存提供器
     * @return 协议快照注册表
     */
    @Bean
    public ProtocolSnapshotRegistry protocolSnapshotRegistry(
            GatewayConfigurationDistributionProperties properties,
            ObjectProvider<ConfigurationSnapshotCache> snapshotCacheProvider
    ) {
        ProtocolSnapshotRegistry registry = new ProtocolSnapshotRegistry(properties.resolvedEnabled());
        if (properties.resolvedEnabled()) {
            snapshotCacheProvider.getObject().restoreInto(registry);
        }
        return registry;
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
     * 把当前生效身份快照的版本、摘要和设备数量加入内部 Actuator 信息。
     *
     * @param registry 运行时身份注册表
     * @return 设备身份快照信息贡献器
     */
    @Bean
    public InfoContributor deviceIdentitySnapshotInfoContributor(
            DeviceSessionRegistry registry,
            ProtocolSnapshotRegistry protocolRegistry
    ) {
        return builder -> builder
                .withDetail("deviceIdentitySnapshot", registry.status())
                .withDetail("protocolSnapshots", protocolRegistry.statuses());
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
            ProtocolSnapshotRegistry protocolRegistry,
            GatewayRabbitProperties properties
    ) {
        return new DeviceMessageNormalizer(
                registry,
                protocolRegistry,
                IdGenerator.timeOrdered(),
                Clock.systemUTC(),
                properties.resolvedMaxPayloadBytes()
        );
    }

    /**
     * 创建固定 JSON v1 定位报文标准化器。
     *
     * @param registry 可信设备身份快照
     * @param properties gateway RabbitMQ 输入边界参数
     * @return 定位报文标准化器
     */
    @Bean
    public LocationMessageNormalizer locationMessageNormalizer(
            DeviceSessionRegistry registry,
            GatewayRabbitProperties properties
    ) {
        return new LocationMessageNormalizer(
                registry,
                IdGenerator.timeOrdered(),
                Clock.systemUTC(),
                properties.resolvedMaxPayloadBytes());
    }

    /**
     * 创建配置发布消息解码器。
     *
     * @param properties RabbitMQ 输入边界参数
     * @return 配置发布解码器
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.gateway.configuration-distribution",
            name = "enabled",
            havingValue = "true")
    public ConfigurationReleaseCodec configurationReleaseCodec(GatewayRabbitProperties properties) {
        return new ConfigurationReleaseCodec(properties.resolvedMaxPayloadBytes());
    }

    /**
     * 创建带 secret 服务令牌的内部快照客户端。
     *
     * @param properties 配置分发参数
     * @return HTTP 配置快照客户端
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.gateway.configuration-distribution",
            name = "enabled",
            havingValue = "true")
    public HttpConfigurationSnapshotClient configurationSnapshotClient(
            GatewayConfigurationDistributionProperties properties
    ) {
        return new HttpConfigurationSnapshotClient(properties);
    }

    /**
     * 创建配置分发低基数指标；精简上下文没有自动配置注册表时使用本地注册表。
     *
     * @param registryProvider Micrometer 注册表提供器
     * @return 配置分发指标记录器
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.gateway.configuration-distribution",
            name = "enabled",
            havingValue = "true")
    public ConfigurationDistributionMetrics configurationDistributionMetrics(
            ObjectProvider<MeterRegistry> registryProvider
    ) {
        return new ConfigurationDistributionMetrics(
                registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 创建配置发布事件消费者。
     *
     * @param codec 配置消息解码器
     * @param client 内部快照客户端
     * @param registry 协议快照注册表
     * @param snapshotCache 最后可用配置本地缓存
     * @param metrics 配置分发低基数指标
     * @return 配置发布消费者
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.gateway.configuration-distribution",
            name = "enabled",
            havingValue = "true")
    public ConfigurationReleaseConsumer configurationReleaseConsumer(
            ConfigurationReleaseCodec codec,
            HttpConfigurationSnapshotClient client,
            ProtocolSnapshotRegistry registry,
            ConfigurationSnapshotCache snapshotCache,
            ConfigurationDistributionMetrics metrics
    ) {
        return new ConfigurationReleaseConsumer(codec, client, registry, snapshotCache, metrics);
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
     * 创建使用平台连接的定位观测确认发布器。
     *
     * @param platformConnectionFactory 平台 vhost 连接
     * @param properties gateway RabbitMQ 参数
     * @return 带 Broker 确认的定位发布器
     */
    @Bean
    public ConfirmedLocationPublisher confirmedLocationPublisher(
            CachingConnectionFactory platformConnectionFactory,
            GatewayRabbitProperties properties
    ) {
        return new ConfirmedLocationPublisher(
                new RabbitTemplate(platformConnectionFactory),
                properties.resolvedConfirmTimeoutMillis());
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
     * 创建独立原始定位消息处理器。
     *
     * @param normalizer 定位报文标准化器
     * @param publisher 定位观测确认发布器
     * @return 原始定位消息处理器
     */
    @Bean
    public LocationMessageConsumer locationMessageConsumer(
            LocationMessageNormalizer normalizer,
            ConfirmedLocationPublisher publisher
    ) {
        return new LocationMessageConsumer(normalizer, publisher);
    }

    /**
     * 创建命令信封和设备 ACK 解码器。
     *
     * @param registry 可信设备身份快照
     * @param properties gateway RabbitMQ 参数
     * @return 命令消息编解码器
     */
    @Bean
    public CommandMessageCodec commandMessageCodec(
            DeviceSessionRegistry registry,
            GatewayRabbitProperties properties
    ) {
        return new CommandMessageCodec(registry, properties.resolvedMaxPayloadBytes());
    }

    /**
     * 创建向设备 MQTT 主题发布命令的确认发布器。
     *
     * @param deviceConnectionFactory 设备 vhost 连接
     * @param registry 可信设备身份快照
     * @param properties gateway RabbitMQ 参数
     * @return MQTT 命令发布器
     */
    @Bean
    public MqttCommandPublisher mqttCommandPublisher(
            CachingConnectionFactory deviceConnectionFactory,
            DeviceSessionRegistry registry,
            GatewayRabbitProperties properties
    ) {
        return new MqttCommandPublisher(
                new RabbitTemplate(deviceConnectionFactory), registry,
                Clock.systemUTC(), properties.resolvedConfirmTimeoutMillis()
        );
    }

    /**
     * 创建命令状态确认发布器。
     *
     * @param platformConnectionFactory 平台 vhost 连接
     * @param properties gateway RabbitMQ 参数
     * @return 命令状态发布器
     */
    @Bean
    public ConfirmedCommandStatusPublisher confirmedCommandStatusPublisher(
            CachingConnectionFactory platformConnectionFactory,
            GatewayRabbitProperties properties
    ) {
        return new ConfirmedCommandStatusPublisher(
                new RabbitTemplate(platformConnectionFactory), IdGenerator.timeOrdered(),
                Clock.systemUTC(), properties.resolvedConfirmTimeoutMillis()
        );
    }

    /**
     * 创建基于 RabbitMQ TTL 的一次性命令超时调度器。
     *
     * @param platformConnectionFactory 平台 vhost 连接
     * @param properties gateway RabbitMQ 参数
     * @return 命令超时调度器
     */
    @Bean
    public RabbitCommandTimeoutScheduler rabbitCommandTimeoutScheduler(
            CachingConnectionFactory platformConnectionFactory,
            GatewayRabbitProperties properties
    ) {
        return new RabbitCommandTimeoutScheduler(
                new RabbitTemplate(platformConnectionFactory), IdGenerator.timeOrdered(),
                Clock.systemUTC(), properties.resolvedConfirmTimeoutMillis()
        );
    }

    /**
     * 创建平台命令消费处理器。
     *
     * @param codec 命令信封解码器
     * @param mqttPublisher MQTT 下行发布器
     * @param statusPublisher 状态事件发布器
     * @param timeoutScheduler 命令回复超时调度器
     * @return 命令消费处理器
     */
    @Bean
    public CommandDispatchConsumer commandDispatchConsumer(
            CommandMessageCodec codec,
            MqttCommandPublisher mqttPublisher,
            ConfirmedCommandStatusPublisher statusPublisher,
            RabbitCommandTimeoutScheduler timeoutScheduler
    ) {
        return new CommandDispatchConsumer(
                codec, mqttPublisher, statusPublisher, timeoutScheduler, Clock.systemUTC()
        );
    }

    /**
     * 创建设备命令 ACK 消费处理器。
     *
     * @param codec ACK 解码器
     * @param statusPublisher 状态事件发布器
     * @return ACK 消费处理器
     */
    @Bean
    public CommandAckConsumer commandAckConsumer(
            CommandMessageCodec codec,
            ConfirmedCommandStatusPublisher statusPublisher
    ) {
        return new CommandAckConsumer(codec, statusPublisher);
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

    /**
     * 创建手工确认的原始定位上行监听容器。
     *
     * @param deviceConnectionFactory 设备 vhost 连接
     * @param consumer 定位消息处理器
     * @param properties gateway RabbitMQ 参数
     * @param snapshotLoader 身份快照加载器，保证首次快照先于消费启动
     * @return 定位上行监听容器
     */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer locationMessageListenerContainer(
            CachingConnectionFactory deviceConnectionFactory,
            LocationMessageConsumer consumer,
            GatewayRabbitProperties properties,
            DeviceIdentitySnapshotLoader snapshotLoader
    ) {
        return listenerContainer(
                deviceConnectionFactory,
                MineTopology.DEVICE_LOCATION_UPLINK_QUEUE,
                consumer,
                properties);
    }

    /**
     * 创建手工确认的设备命令 ACK 监听容器。
     *
     * @param deviceConnectionFactory 设备 vhost 连接
     * @param consumer ACK 处理器
     * @param properties gateway RabbitMQ 参数
     * @param snapshotLoader 身份快照加载器
     * @return ACK 监听容器
     */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer commandAckListenerContainer(
            CachingConnectionFactory deviceConnectionFactory,
            CommandAckConsumer consumer,
            GatewayRabbitProperties properties,
            DeviceIdentitySnapshotLoader snapshotLoader
    ) {
        return listenerContainer(
                deviceConnectionFactory, MineTopology.DEVICE_COMMAND_ACK_QUEUE,
                consumer, properties
        );
    }

    /**
     * 创建手工确认的平台命令监听容器。
     *
     * @param platformConnectionFactory 平台 vhost 连接
     * @param consumer 命令处理器
     * @param properties gateway RabbitMQ 参数
     * @return 命令监听容器
     */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer commandDispatchListenerContainer(
            CachingConnectionFactory platformConnectionFactory,
            CommandDispatchConsumer consumer,
            GatewayRabbitProperties properties
    ) {
        return listenerContainer(
                platformConnectionFactory, MineTopology.COMMAND_QUEUE,
                consumer, properties
        );
    }

    /**
     * 创建手工确认的动态配置监听容器。
     *
     * @param platformConnectionFactory 平台 vhost 连接
     * @param consumer 配置发布消费者
     * @param properties RabbitMQ 参数
     * @return 配置发布监听容器
     */
    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(
            prefix = "mine.gateway.configuration-distribution",
            name = "enabled",
            havingValue = "true")
    public SimpleMessageListenerContainer configurationReleaseListenerContainer(
            CachingConnectionFactory platformConnectionFactory,
            ConfigurationReleaseConsumer consumer,
            GatewayRabbitProperties properties
    ) {
        return listenerContainer(
                platformConnectionFactory, MineTopology.CONFIG_QUEUE,
                consumer, properties
        );
    }

    private SimpleMessageListenerContainer listenerContainer(
            CachingConnectionFactory connectionFactory,
            String queue,
            ChannelAwareMessageListener consumer,
            GatewayRabbitProperties properties
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queue);
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
