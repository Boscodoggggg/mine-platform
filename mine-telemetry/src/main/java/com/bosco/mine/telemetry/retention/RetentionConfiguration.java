package com.bosco.mine.telemetry.retention;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;

/**
 * 独立于消息消费开关装配数据保留执行、指标和单线程固定延迟调度。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetentionProperties.class)
@ConditionalOnProperty(
        prefix = "mine.telemetry.retention", name = "enabled", havingValue = "true")
public class RetentionConfiguration {

    /** 创建使用主 TimescaleDB 数据源的数据保留执行仓储。 */
    @Bean
    public RetentionExecutionRepository retentionExecutionRepository(DataSource dataSource) {
        return new RetentionExecutionRepository(dataSource);
    }

    /** 创建带有界批次、周期和失联恢复参数的数据保留执行器。 */
    @Bean
    public RetentionExecutor retentionExecutor(
            RetentionExecutionRepository repository,
            RetentionProperties properties
    ) {
        return new RetentionExecutor(
                repository,
                properties.resolvedInstanceId(),
                properties.resolvedBatchSize(),
                properties.resolvedMaxCycles(),
                properties.resolvedStaleExecutionTimeout()
        );
    }

    /** 创建固定状态和数据集标签的数据保留指标。 */
    @Bean
    public RetentionMetrics retentionMetrics(MeterRegistry registry) {
        return new RetentionMetrics(registry);
    }

    /** 创建只运行数据保留任务的单线程调度器。 */
    @Bean(name = "retentionTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler retentionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("telemetry-retention-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    /** 创建应用启动后注册固定延迟任务并可在关闭时取消的协调器。 */
    @Bean(destroyMethod = "close")
    public RetentionScheduler retentionScheduler(
            RetentionExecutor executor,
            RetentionMetrics metrics,
            @Qualifier("retentionTaskScheduler") ThreadPoolTaskScheduler taskScheduler,
            RetentionProperties properties
    ) {
        return new RetentionScheduler(
                executor,
                metrics,
                taskScheduler,
                properties.resolvedInitialDelay(),
                properties.resolvedInterval()
        );
    }
}
