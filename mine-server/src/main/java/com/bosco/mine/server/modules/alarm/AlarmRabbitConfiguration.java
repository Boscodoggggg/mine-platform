package com.bosco.mine.server.modules.alarm;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.modules.alarm.messaging.AlarmEventConsumer;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 使用独立最小权限账号装配告警消息消费者和运行指标。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlarmRabbitProperties.class)
@ConditionalOnProperty(prefix = "mine.alarm.rabbit", name = "enabled", havingValue = "true")
public class AlarmRabbitConfiguration {

    /** 创建告警队列只读连接，密码只从只读密钥文件加载。 */
    @Bean(name = "alarmConnectionFactory", destroyMethod = "destroy")
    public CachingConnectionFactory alarmConnectionFactory(AlarmRabbitProperties properties) {
        AlarmRabbitProperties.Account account = properties.account();
        if (account == null || account.username() == null || account.username().isBlank()
                || account.passwordFile() == null) {
            throw new IllegalArgumentException("告警 RabbitMQ 账号配置不完整");
        }
        int port = properties.resolvedPort();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("告警 RabbitMQ 端口不合法");
        }
        CachingConnectionFactory factory = new CachingConnectionFactory(
                properties.resolvedHost(), port);
        factory.setVirtualHost(MineTopology.PLATFORM_VHOST);
        factory.setUsername(account.username().strip());
        factory.setPassword(readSecret(account.passwordFile()));
        return factory;
    }

    /** 创建告警事实消费者。 */
    @Bean
    public AlarmEventConsumer alarmEventConsumer(
            AlarmEventService service,
            AlarmMetrics metrics
    ) {
        return new AlarmEventConsumer(service, metrics);
    }

    /** 创建手工确认且遇到缺失队列立即失败的告警监听容器。 */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer alarmEventListenerContainer(
            @Qualifier("alarmConnectionFactory") CachingConnectionFactory connectionFactory,
            AlarmEventConsumer consumer,
            AlarmRabbitProperties properties
    ) {
        int prefetch = properties.resolvedPrefetch();
        if (prefetch < 1 || prefetch > 10_000) {
            throw new IllegalArgumentException("告警 RabbitMQ 预取数不合法");
        }
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                connectionFactory);
        container.setQueueNames(MineTopology.SERVER_ALARM_EVENT_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(prefetch);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(4);
        container.setDefaultRequeueRejected(false);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(properties.resolvedAutoStartup());
        container.setMessageListener(consumer);
        return container;
    }

    private String readSecret(Path path) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("告警 RabbitMQ 密码文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取告警 RabbitMQ 密码文件: " + path, exception);
        }
    }
}
