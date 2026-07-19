package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.messaging.topology.MineTopology;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;

/**
 * 装配 RabbitMQ 到本服务实例 WebSocket 会话的事件驱动桥接。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties.class)
@ConditionalOnProperty(
        prefix = "mine.realtime",
        name = {"enabled", "rabbit-enabled"},
        havingValue = "true"
)
public class RealtimeRabbitConfiguration {

    /**
     * 创建平台内部 RabbitMQ 连接，密码只从只读密钥文件读取。
     *
     * @param properties 实时通道配置
     * @return 实时事件连接工厂
     */
    @Bean(name = "realtimeConnectionFactory", destroyMethod = "destroy")
    public CachingConnectionFactory realtimeConnectionFactory(RealtimeProperties properties) {
        RealtimeProperties.Account account = properties.account();
        if (account == null || account.username() == null || account.username().isBlank()) {
            throw new IllegalArgumentException("实时 RabbitMQ 服务账号不能为空");
        }
        if (account.passwordFile() == null) {
            throw new IllegalArgumentException("实时 RabbitMQ 密码文件不能为空");
        }
        CachingConnectionFactory factory = new CachingConnectionFactory(
                properties.resolvedRabbitHost(), properties.resolvedRabbitPort()
        );
        factory.setVirtualHost(MineTopology.PLATFORM_VHOST);
        factory.setUsername(account.username().strip());
        factory.setPassword(readSecret(account.passwordFile()));
        return factory;
    }

    /**
     * 声明已持久化最新值实时事件交换机。
     *
     * @return 实时主题交换机
     */
    @Bean
    public TopicExchange realtimeExchange() {
        return new TopicExchange(MineTopology.REALTIME_EXCHANGE, true, false);
    }

    /**
     * 创建本服务实例独占的临时队列，保证多实例不会竞争消费。
     *
     * @param properties 实时通道配置
     * @return 本实例实时队列
     */
    @Bean
    public Queue realtimeQueue(RealtimeProperties properties) {
        return QueueBuilder.nonDurable(properties.resolvedQueueName())
                .exclusive()
                .autoDelete()
                .withArgument("x-queue-type", "classic")
                .build();
    }

    /**
     * 将本实例队列绑定到全部实时遥测事件。
     *
     * @param realtimeQueue 本实例实时队列
     * @param realtimeExchange 实时主题交换机
     * @return 实时遥测绑定
     */
    @Bean
    public Binding realtimeTelemetryBinding(
            Queue realtimeQueue,
            TopicExchange realtimeExchange
    ) {
        return BindingBuilder.bind(realtimeQueue)
                .to(realtimeExchange)
                .with(MineTopology.REALTIME_TELEMETRY_ROUTING_PATTERN);
    }

    /**
     * 将本实例队列绑定到全部矿区位置实时事件。
     *
     * @param realtimeQueue 本实例实时队列
     * @param realtimeExchange 实时主题交换机
     * @return 矿区位置实时绑定
     */
    @Bean
    public Binding realtimeLocationBinding(
            Queue realtimeQueue,
            TopicExchange realtimeExchange
    ) {
        return BindingBuilder.bind(realtimeQueue)
                .to(realtimeExchange)
                .with(MineTopology.REALTIME_LOCATION_ROUTING_PATTERN);
    }

    /**
     * 将本实例队列绑定到全部矿区空间事件实时主题。
     *
     * @param realtimeQueue 本实例实时队列
     * @param realtimeExchange 实时主题交换机
     * @return 矿区空间事件实时绑定
     */
    @Bean
    public Binding realtimeSpatialBinding(
            Queue realtimeQueue,
            TopicExchange realtimeExchange
    ) {
        return BindingBuilder.bind(realtimeQueue)
                .to(realtimeExchange)
                .with(MineTopology.REALTIME_SPATIAL_ROUTING_PATTERN);
    }

    /**
     * 创建实时拓扑声明器，连接恢复后自动重建本实例临时队列和绑定。
     *
     * @param realtimeConnectionFactory 实时事件连接工厂
     * @param properties 实时通道配置
     * @return RabbitMQ 管理器
     */
    @Bean
    public RabbitAdmin realtimeRabbitAdmin(
            CachingConnectionFactory realtimeConnectionFactory,
            RealtimeProperties properties
    ) {
        RabbitAdmin admin = new RabbitAdmin(realtimeConnectionFactory);
        admin.setAutoStartup(properties.resolvedRabbitAutoStartup());
        return admin;
    }

    /**
     * 创建实时遥测事件消费者。
     *
     * @param registry 本实例会话注册表
     * @return 实时遥测消费者
     */
    @Bean
    public RealtimeTelemetryConsumer realtimeTelemetryConsumer(
            RealtimeSessionRegistry registry
    ) {
        return new RealtimeTelemetryConsumer(registry);
    }

    /**
     * 创建矿区位置实时事件消费者。
     *
     * @param registry 本实例会话注册表
     * @return 位置实时消费者
     */
    @Bean
    public RealtimeLocationConsumer realtimeLocationConsumer(
            RealtimeSessionRegistry registry
    ) {
        return new RealtimeLocationConsumer(registry);
    }

    /**
     * 创建矿区空间事件实时消费者。
     *
     * @param registry 本实例会话注册表
     * @return 空间事件实时消费者
     */
    @Bean
    public RealtimeSpatialConsumer realtimeSpatialConsumer(
            RealtimeSessionRegistry registry
    ) {
        return new RealtimeSpatialConsumer(registry);
    }

    /**
     * 创建事件驱动、手工确认的 RabbitMQ 监听容器，不启动任何数据库轮询任务。
     *
     * @param realtimeConnectionFactory 实时事件连接工厂
     * @param telemetryConsumer 实时遥测消费者
     * @param locationConsumer 位置实时消费者
     * @param spatialConsumer 空间事件实时消费者
     * @param properties 实时通道配置
     * @param realtimeRabbitAdmin 实时拓扑声明器
     * @param realtimeQueue 本实例实时队列
     * @return 实时事件监听容器
     */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer realtimeMessageListenerContainer(
            CachingConnectionFactory realtimeConnectionFactory,
            RealtimeTelemetryConsumer telemetryConsumer,
            RealtimeLocationConsumer locationConsumer,
            RealtimeSpatialConsumer spatialConsumer,
            RealtimeProperties properties,
            RabbitAdmin realtimeRabbitAdmin,
            Queue realtimeQueue
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                realtimeConnectionFactory
        );
        container.setAmqpAdmin(realtimeRabbitAdmin);
        container.setQueues(realtimeQueue);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(properties.resolvedPrefetch());
        container.setDefaultRequeueRejected(true);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(properties.resolvedRabbitAutoStartup());
        container.setMessageListener(new RealtimeEventConsumer(
                telemetryConsumer, locationConsumer, spatialConsumer));
        return container;
    }

    private String readSecret(java.nio.file.Path path) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("实时 RabbitMQ 密码文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取实时 RabbitMQ 密码文件: " + path, exception);
        }
    }
}
