package com.bosco.mine.server.modules.mine.operation.outbox;

import com.bosco.mine.messaging.topology.MineTopology;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 装配生产运营 Outbox 的独立账号、发布确认、随机租约和周期恢复链路。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperationOutboxProperties.class)
@ConditionalOnProperty(prefix = "mine.operation-outbox", name = "enabled", havingValue = "true")
public class OperationOutboxConfiguration {

    /**
     * 创建只具备运营实时交换机写权限的 RabbitMQ 连接。
     *
     * @param properties 运营 Outbox 配置
     * @return 发布连接工厂
     */
    @Bean(name = "operationOutboxConnectionFactory", destroyMethod = "destroy")
    public CachingConnectionFactory operationOutboxConnectionFactory(
            OperationOutboxProperties properties
    ) {
        OperationOutboxProperties.Account account = properties.account();
        if (account == null || account.username() == null || account.username().isBlank()
                || account.passwordFile() == null) {
            throw new IllegalArgumentException("运营 Outbox RabbitMQ 账号配置不完整");
        }
        CachingConnectionFactory factory = new CachingConnectionFactory(
                properties.resolvedRabbitHost(), properties.resolvedRabbitPort());
        factory.setVirtualHost(MineTopology.PLATFORM_VHOST);
        factory.setUsername(account.username().strip());
        factory.setPassword(readSecret(account.passwordFile()));
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        return factory;
    }

    /** 创建运营 Outbox RabbitMQ 操作器。 */
    @Bean(name = "operationOutboxRabbitTemplate")
    public RabbitTemplate operationOutboxRabbitTemplate(
            @Qualifier("operationOutboxConnectionFactory")
            CachingConnectionFactory connectionFactory
    ) {
        return new RabbitTemplate(connectionFactory);
    }

    /** 创建等待 Broker 确认的强类型运营事件发布器。 */
    @Bean
    public RabbitOperationEventPublisher rabbitOperationEventPublisher(
            @Qualifier("operationOutboxRabbitTemplate") RabbitTemplate rabbitTemplate,
            OperationOutboxProperties properties
    ) {
        return new RabbitOperationEventPublisher(
                rabbitTemplate, properties.resolvedConfirmTimeout().toMillis());
    }

    /** 创建使用数据库随机租约推进状态的运营 Outbox 中继器。 */
    @Bean
    public OperationOutboxRelay operationOutboxRelay(
            OperationOutboxRepository repository,
            RabbitOperationEventPublisher publisher,
            OperationOutboxProperties properties
    ) {
        return new OperationOutboxRelay(
                repository, publisher, Clock.systemUTC(),
                properties.resolvedLeaseDuration(), properties.resolvedMaxAttempts(),
                properties.resolvedRecoveryLimit(), properties.resolvedRetryBaseDelay(),
                properties.resolvedRetryMaxDelay());
    }

    /** 创建不阻塞 Web 请求线程的虚拟线程发布执行器。 */
    @Bean(name = "operationOutboxExecutor", destroyMethod = "close")
    public ExecutorService operationOutboxExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 创建单线程周期恢复调度器。 */
    @Bean(name = "operationOutboxTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler operationOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("operation-outbox-recovery-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 创建启动和周期有界恢复协调器。 */
    @Bean
    public OperationOutboxRecovery operationOutboxRecovery(
            OperationOutboxRelay relay,
            @Qualifier("operationOutboxExecutor") ExecutorService executor,
            @Qualifier("operationOutboxTaskScheduler") ThreadPoolTaskScheduler scheduler,
            OperationOutboxProperties properties
    ) {
        return new OperationOutboxRecovery(
                relay, executor, scheduler, properties.resolvedRecoveryInterval());
    }

    private String readSecret(java.nio.file.Path path) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("运营 Outbox RabbitMQ 密码文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "无法读取运营 Outbox RabbitMQ 密码文件: " + path, exception);
        }
    }
}
