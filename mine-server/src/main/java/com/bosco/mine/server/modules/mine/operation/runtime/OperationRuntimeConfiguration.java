package com.bosco.mine.server.modules.mine.operation.runtime;

import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.modules.mine.operation.messaging.OperationSpatialEventConsumer;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.ImmediateRequeueMessageRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 使用独立最小权限账号装配运营空间事件持久消费者。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperationRuntimeConfiguration.RuntimeProperties.class)
@ConditionalOnProperty(
        prefix = "mine.operation-runtime",
        name = "rabbit-enabled",
        havingValue = "true"
)
public class OperationRuntimeConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            OperationRuntimeConfiguration.class);

    /** 创建运营空间事件只读连接，密码只从只读密钥文件加载。 */
    @Bean(name = "operationConnectionFactory", destroyMethod = "destroy")
    public CachingConnectionFactory operationConnectionFactory(RuntimeProperties properties) {
        Account account = properties.account();
        if (account == null || account.username() == null || account.username().isBlank()
                || account.passwordFile() == null) {
            throw new IllegalArgumentException("运营 RabbitMQ 账号配置不完整");
        }
        int port = properties.resolvedPort();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("运营 RabbitMQ 端口不合法");
        }
        CachingConnectionFactory factory = new CachingConnectionFactory(
                properties.resolvedHost(), port);
        factory.setVirtualHost(MineTopology.PLATFORM_VHOST);
        factory.setUsername(account.username().strip());
        factory.setPassword(readSecret(account.passwordFile()));
        return factory;
    }

    /** 创建事务性运营空间事件消费者。 */
    @Bean
    public OperationSpatialEventConsumer operationSpatialEventConsumer(
            OperationEventQueue queue
    ) {
        return new OperationSpatialEventConsumer(queue);
    }

    /**
     * 创建入口数据库故障的有界指数退避策略；耗尽本轮尝试后仍交由 Broker 重入队。
     */
    @Bean(name = "operationIngressRetryAdvice")
    public MethodInterceptor operationIngressRetryAdvice(RuntimeProperties properties) {
        int attempts = properties.resolvedIngressRetryAttempts();
        Duration baseDelay = properties.resolvedIngressRetryBaseDelay();
        Duration maxDelay = properties.resolvedIngressRetryMaxDelay();
        if (attempts < 1 || attempts > 20
                || baseDelay.isZero() || baseDelay.isNegative()
                || maxDelay.isZero() || maxDelay.isNegative()
                || baseDelay.compareTo(maxDelay) > 0
                || maxDelay.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("运营事件入口退避配置不合法");
        }
        return RetryInterceptorBuilder.stateless()
                .maxRetries(attempts - 1)
                .backOffOptions(baseDelay.toMillis(), 2.0, maxDelay.toMillis())
                .recoverer(new ImmediateRequeueMessageRecoverer())
                .build();
    }

    /** 创建手工确认、缺失持久队列立即失败的运营空间事件监听容器。 */
    @Bean(destroyMethod = "destroy")
    public SimpleMessageListenerContainer operationEventListenerContainer(
            @Qualifier("operationConnectionFactory") CachingConnectionFactory connectionFactory,
            OperationSpatialEventConsumer consumer,
            @Qualifier("operationIngressRetryAdvice") MethodInterceptor retryAdvice,
            RuntimeProperties properties
    ) {
        int prefetch = properties.resolvedPrefetch();
        if (prefetch < 1 || prefetch > 10_000) {
            throw new IllegalArgumentException("运营 RabbitMQ 预取数不合法");
        }
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                connectionFactory);
        container.setQueueNames(MineTopology.SERVER_OPERATION_SPATIAL_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(prefetch);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(4);
        container.setDefaultRequeueRejected(false);
        container.setAdviceChain(retryAdvice);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(properties.resolvedAutoStartup());
        container.setMessageListener(consumer);
        return container;
    }

    /** 创建运营事件持久处理专用单线程调度器。 */
    @Bean(name = "operationAutomationTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler operationAutomationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("operation-automation-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * 服务启动后持续有界排空持久事件；单轮异常被隔离，避免调度任务永久停止。
     */
    @Bean
    public ApplicationRunner operationAutomationRunner(
            @Qualifier("operationAutomationTaskScheduler") ThreadPoolTaskScheduler scheduler,
            OperationAutomationWorker worker,
            RuntimeProperties properties
    ) {
        Duration interval = properties.resolvedAutomationInterval();
        Duration stepTimeout = properties.resolvedStepTimeout();
        int drainLimit = properties.resolvedDrainLimit();
        if (interval.compareTo(Duration.ofMillis(10)) < 0
                || interval.compareTo(Duration.ofMinutes(1)) > 0
                || stepTimeout.isZero() || stepTimeout.isNegative()
                || stepTimeout.compareTo(Duration.ofDays(7)) > 0
                || drainLimit < 1 || drainLimit > 10_000) {
            throw new IllegalArgumentException("运营自动处理调度配置不合法");
        }
        OperationEventQueue.ClaimPolicy policy = properties.claimPolicy();
        return arguments -> scheduler.scheduleWithFixedDelay(() -> {
            try {
                worker.drainBounded(policy, stepTimeout, drainLimit);
            } catch (RuntimeException exception) {
                LOGGER.error("运营持久事件单轮排空失败，下轮将继续恢复", exception);
            }
        }, interval);
    }

    private String readSecret(Path path) {
        try {
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("运营 RabbitMQ 密码文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取运营 RabbitMQ 密码文件: " + path, exception);
        }
    }

    /**
     * 运营事件 RabbitMQ、乱序缓冲、租约和退避配置。
     *
     * @param rabbitEnabled 是否启用 RabbitMQ 持久消费
     * @param autoStartup 监听容器是否随服务启动
     * @param host RabbitMQ 主机
     * @param port RabbitMQ AMQP 端口
     * @param prefetch 单消费者预取数
     * @param ingressRetryAttempts 单次投递内的入口持久化尝试总数
     * @param ingressRetryBaseDelay 入口持久化首次重试等待
     * @param ingressRetryMaxDelay 入口持久化最大重试等待
     * @param allowedLateness 允许乱序窗口
     * @param idleFlush 空闲排空等待
     * @param leaseDuration 单次处理租约
     * @param maxAttempts 最大处理尝试次数
     * @param retryBaseDelay 首次失败退避
     * @param retryMaxDelay 最大失败退避
     * @param automationInterval 自动处理轮询间隔
     * @param stepTimeout 单个运输步骤最大持续时间
     * @param drainLimit 单轮最大处理事件数
     * @param account 运营只读消费账号
     */
    @ConfigurationProperties("mine.operation-runtime")
    public record RuntimeProperties(
            Boolean rabbitEnabled,
            Boolean autoStartup,
            String host,
            Integer port,
            Integer prefetch,
            Integer ingressRetryAttempts,
            Duration ingressRetryBaseDelay,
            Duration ingressRetryMaxDelay,
            Duration allowedLateness,
            Duration idleFlush,
            Duration leaseDuration,
            Integer maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Duration automationInterval,
            Duration stepTimeout,
            Integer drainLimit,
            Account account
    ) {
        /** 返回 RabbitMQ 主机。 */
        public String resolvedHost() {
            return host == null || host.isBlank() ? "rabbitmq" : host.strip();
        }

        /** 返回 RabbitMQ AMQP 端口。 */
        public int resolvedPort() {
            return port == null ? 5672 : port;
        }

        /** 返回监听容器自动启动开关。 */
        public boolean resolvedAutoStartup() {
            return autoStartup == null || autoStartup;
        }

        /** 返回单消费者预取数。 */
        public int resolvedPrefetch() {
            return prefetch == null ? 50 : prefetch;
        }

        /** 返回单次 Broker 投递内的持久化尝试总数。 */
        public int resolvedIngressRetryAttempts() {
            return ingressRetryAttempts == null ? 5 : ingressRetryAttempts;
        }

        /** 返回入口持久化首次重试等待。 */
        public Duration resolvedIngressRetryBaseDelay() {
            return ingressRetryBaseDelay == null
                    ? Duration.ofSeconds(1) : ingressRetryBaseDelay;
        }

        /** 返回入口持久化最大重试等待。 */
        public Duration resolvedIngressRetryMaxDelay() {
            return ingressRetryMaxDelay == null
                    ? Duration.ofSeconds(30) : ingressRetryMaxDelay;
        }

        /** 返回经过统一校验的事件认领策略。 */
        public OperationEventQueue.ClaimPolicy claimPolicy() {
            return new OperationEventQueue.ClaimPolicy(
                    allowedLateness == null ? Duration.ofSeconds(30) : allowedLateness,
                    idleFlush == null ? Duration.ofSeconds(5) : idleFlush,
                    leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration,
                    maxAttempts == null ? 8 : maxAttempts,
                    retryBaseDelay == null ? Duration.ofSeconds(1) : retryBaseDelay,
                    retryMaxDelay == null ? Duration.ofMinutes(1) : retryMaxDelay);
        }

        /** 返回自动处理轮询间隔。 */
        public Duration resolvedAutomationInterval() {
            return automationInterval == null ? Duration.ofMillis(250) : automationInterval;
        }

        /** 返回单个运输步骤最大持续时间。 */
        public Duration resolvedStepTimeout() {
            return stepTimeout == null ? Duration.ofHours(2) : stepTimeout;
        }

        /** 返回单轮最大处理事件数。 */
        public int resolvedDrainLimit() {
            return drainLimit == null ? 100 : drainLimit;
        }
    }

    /**
     * 运营空间事件最小权限消费账号。
     *
     * @param username RabbitMQ 用户名
     * @param passwordFile 只读密码文件
     */
    public record Account(String username, Path passwordFile) {
    }
}
