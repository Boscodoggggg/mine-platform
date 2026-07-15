package com.bosco.mine.telemetry.bootstrap;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.telemetry.consumer.NormalizedTelemetryConsumer;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.realtime.ConfirmedRealtimePublisher;
import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 装配遥测服务的 RabbitMQ 批消费者、Redis 最新值仓储和 TimescaleDB 历史批写器。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryRabbitProperties.class)
@ConditionalOnProperty(prefix = "mine.telemetry.rabbit", name = "enabled", havingValue = "true")
public class TelemetryStorageConfiguration {

    /**
     * 创建 Redis 实时最新值仓储。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @return 最新值仓储
     */
    @Bean
    public LatestValueRepository latestValueRepository(StringRedisTemplate redisTemplate) {
        return new LatestValueRepository(redisTemplate);
    }

    /**
     * 创建 TimescaleDB 事务批写器。
     *
     * @param dataSource 时序数据库连接池
     * @return 遥测历史批写器
     */
    @Bean
    public TelemetryBatchWriter telemetryBatchWriter(DataSource dataSource) {
        return new TelemetryBatchWriter(dataSource);
    }

    /**
     * 创建标准化遥测批消费者。
     *
     * @param latestValues Redis 实时最新值仓储
     * @param batchWriter  TimescaleDB 历史批写器
     * @return 批消息处理器
     */
    @Bean
    public NormalizedTelemetryConsumer normalizedTelemetryConsumer(
            LatestValueRepository latestValues,
            TelemetryBatchWriter batchWriter,
            ConfirmedRealtimePublisher realtimePublisher) {
        return new NormalizedTelemetryConsumer(latestValues, batchWriter, realtimePublisher);
    }

    /**
     * 创建等待 Broker 确认的实时遥测发布器。
     *
     * @param telemetryConnectionFactory 平台 vhost 连接
     * @param properties 遥测 RabbitMQ 参数
     * @return 实时事件发布器
     */
    @Bean
    public ConfirmedRealtimePublisher confirmedRealtimePublisher(
            CachingConnectionFactory telemetryConnectionFactory,
            TelemetryRabbitProperties properties
    ) {
        return new ConfirmedRealtimePublisher(
                new RabbitTemplate(telemetryConnectionFactory),
                positive(properties.resolvedConfirmTimeoutMillis(), "confirmTimeoutMillis")
        );
    }

    /**
     * 创建访问平台 vhost 的遥测最小权限连接，用于消费标准遥测并发布实时事件。
     *
     * @param properties 遥测 RabbitMQ 参数
     * @return 平台消息消费连接工厂
     */
    @Bean(destroyMethod = "destroy")
    public CachingConnectionFactory telemetryConnectionFactory(TelemetryRabbitProperties properties) {
        TelemetryRabbitProperties.Account account = properties.account();
        if (account == null || account.username() == null || account.username().isBlank()) {
            throw new IllegalArgumentException("遥测 RabbitMQ 服务账号不能为空");
        }
        if (account.passwordFile() == null) {
            throw new IllegalArgumentException("遥测 RabbitMQ 密码文件不能为空");
        }
        CachingConnectionFactory factory = new CachingConnectionFactory(
                properties.resolvedHost(), positive(properties.resolvedPort(), "port")
        );
        factory.setVirtualHost(MineTopology.PLATFORM_VHOST);
        factory.setUsername(account.username());
        factory.setPassword(readSecret(account.passwordFile()));
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        return factory;
    }

    /**
     * 创建手工确认、消费者侧凑批的 RabbitMQ 监听容器。
     *
     * @param telemetryConnectionFactory 平台 vhost 遥测服务连接
     * @param consumer                    标准化遥测批消费者
     * @param properties                  遥测 RabbitMQ 参数
     * @return 遥测消息监听容器
     */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer telemetryMessageListenerContainer(
            CachingConnectionFactory telemetryConnectionFactory,
            NormalizedTelemetryConsumer consumer,
            TelemetryRabbitProperties properties) {
        int batchSize = positive(properties.resolvedBatchSize(), "batchSize");
        int prefetch = positive(properties.resolvedPrefetch(), "prefetch");
        if (prefetch < batchSize) {
            throw new IllegalArgumentException("prefetch 不能小于 batchSize");
        }
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                telemetryConnectionFactory
        );
        container.setQueueNames(MineTopology.TELEMETRY_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(prefetch);
        container.setBatchSize(batchSize);
        container.setConsumerBatchEnabled(true);
        container.setReceiveTimeout(positive(properties.resolvedReceiveTimeoutMillis(), "receiveTimeoutMillis"));
        container.setBatchReceiveTimeout(properties.resolvedReceiveTimeoutMillis());
        container.setDefaultRequeueRejected(true);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(properties.resolvedAutoStartup());
        container.setMessageListener(consumer);
        return container;
    }

    private String readSecret(java.nio.file.Path path) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("遥测 RabbitMQ 密码文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取遥测 RabbitMQ 密码文件: " + path, exception);
        }
    }

    private int positive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return value;
    }

    private long positive(long value, String field) {
        if (value < 1L) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return value;
    }
}
