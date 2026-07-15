package com.bosco.mine.server.modules.command.outbox;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.modules.command.status.CommandStatusConsumer;
import com.bosco.mine.server.modules.command.status.CommandStatusService;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 装配命令 Outbox 的独立 RabbitMQ 账号、确认发布器、事件调度器和状态消费者。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommandOutboxProperties.class)
@ConditionalOnProperty(prefix = "mine.command-outbox", name = "enabled", havingValue = "true")
public class CommandOutboxConfiguration {

    /**
     * 创建最小写权限的命令发布连接。
     */
    @Bean(name = "commandOutboxConnectionFactory", destroyMethod = "destroy")
    public CachingConnectionFactory commandOutboxConnectionFactory(CommandOutboxProperties properties) {
        CommandOutboxProperties.Account account = properties.account();
        if (account == null || account.username() == null || account.username().isBlank()
                || account.passwordFile() == null) {
            throw new IllegalArgumentException("命令 Outbox RabbitMQ 账号配置不完整");
        }
        CachingConnectionFactory factory = new CachingConnectionFactory(
                properties.resolvedRabbitHost(), properties.resolvedRabbitPort()
        );
        factory.setVirtualHost(MineTopology.PLATFORM_VHOST);
        factory.setUsername(account.username().strip());
        factory.setPassword(readSecret(account.passwordFile()));
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        return factory;
    }

    /**
     * 创建 RabbitMQ 操作器。
     */
    @Bean(name = "commandOutboxRabbitTemplate")
    public RabbitTemplate commandOutboxRabbitTemplate(
            @Qualifier("commandOutboxConnectionFactory") CachingConnectionFactory connectionFactory
    ) {
        return new RabbitTemplate(connectionFactory);
    }

    /**
     * 创建等待发布确认的命令事件发布器。
     */
    @Bean
    public OutboxEventPublisher commandOutboxEventPublisher(
            @Qualifier("commandOutboxRabbitTemplate") RabbitTemplate rabbitTemplate,
            CommandOutboxProperties properties
    ) {
        return new RabbitCommandOutboxPublisher(
                rabbitTemplate, properties.resolvedConfirmTimeout().toMillis()
        );
    }

    /**
     * 创建只服务于已知事件重试时间点的单线程调度器。
     */
    @Bean(name = "commandOutboxTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler commandOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("command-outbox-retry-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * 创建虚拟线程执行器，避免发布确认阻塞 HTTP 请求线程。
     */
    @Bean(name = "commandOutboxExecutor", destroyMethod = "close")
    public ExecutorService commandOutboxExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 创建精确事件重试调度器。
     */
    @Bean
    public KnownEventRetryScheduler knownEventRetryScheduler(
            @Qualifier("commandOutboxTaskScheduler") ThreadPoolTaskScheduler scheduler,
            ObjectProvider<CommandOutboxRelay> relayProvider
    ) {
        return (eventId, retryAt) -> scheduler.schedule(
                () -> relayProvider.getObject().publishById(eventId), retryAt
        );
    }

    /**
     * 创建命令 Outbox 中继器。
     */
    @Bean
    public CommandOutboxRelay commandOutboxRelay(
            OutboxRelayRepository repository,
            OutboxEventPublisher publisher,
            KnownEventRetryScheduler retryScheduler,
            CommandOutboxProperties properties
    ) {
        return new CommandOutboxRelay(
                repository, publisher, retryScheduler, properties.resolvedInstanceId(),
                properties.resolvedLeaseDuration(), properties.resolvedMaxAttempts(),
                properties.resolvedRecoveryLimit(), properties.resolvedRetryBaseDelay(),
                properties.resolvedRetryMaxDelay()
        );
    }

    /**
     * 创建事务提交事件监听器。
     */
    @Bean
    public CommandOutboxReadyListener commandOutboxReadyListener(
            CommandOutboxRelay relay,
            @Qualifier("commandOutboxExecutor") ExecutorService executor
    ) {
        return new CommandOutboxReadyListener(relay, executor);
    }

    /**
     * 创建启动和 RabbitMQ 重连恢复协调器。
     */
    @Bean
    public CommandOutboxRecovery commandOutboxRecovery(
            CommandOutboxRelay relay,
            @Qualifier("commandOutboxConnectionFactory") CachingConnectionFactory connectionFactory,
            @Qualifier("commandOutboxExecutor") ExecutorService executor
    ) {
        return new CommandOutboxRecovery(relay, connectionFactory, executor);
    }

    /**
     * 创建命令状态消息消费者。
     *
     * @param service 命令状态服务
     * @return 命令状态消费者
     */
    @Bean
    public CommandStatusConsumer commandStatusConsumer(CommandStatusService service) {
        return new CommandStatusConsumer(service);
    }

    /**
     * 创建使用手工确认的命令状态监听容器。
     *
     * @param connectionFactory 命令专用 RabbitMQ 连接
     * @param consumer 命令状态消费者
     * @param properties 命令消息配置
     * @return 命令状态监听容器
     */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer commandStatusListenerContainer(
            @Qualifier("commandOutboxConnectionFactory") CachingConnectionFactory connectionFactory,
            CommandStatusConsumer consumer,
            CommandOutboxProperties properties
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(MineTopology.COMMAND_STATUS_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(properties.resolvedStatusPrefetch());
        container.setDefaultRequeueRejected(false);
        container.setMessageListener(consumer);
        return container;
    }

    private String readSecret(java.nio.file.Path path) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("命令 Outbox RabbitMQ 密码文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取命令 Outbox RabbitMQ 密码文件: " + path, exception);
        }
    }
}
