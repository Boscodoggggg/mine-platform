package com.bosco.mine.telemetry.aggregation;

import com.bosco.mine.telemetry.bootstrap.StrategyDistributionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;

/**
 * 独立于消息消费开关装配数值聚合执行、指标和单线程固定延迟调度。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AggregationProperties.class, StrategyDistributionProperties.class
})
@ConditionalOnProperty(
        prefix = "mine.telemetry.aggregation", name = "enabled", havingValue = "true")
public class AggregationConfiguration {

    /** 创建使用主 TimescaleDB 数据源的数值聚合执行仓储。 */
    @Bean
    public AggregationExecutionRepository aggregationExecutionRepository(DataSource dataSource) {
        return new AggregationExecutionRepository(dataSource);
    }

    /** 创建带有界批次、周期和失联恢复参数的数值聚合执行器。 */
    @Bean
    public AggregationExecutor aggregationExecutor(
            AggregationExecutionRepository repository,
            AggregationProperties properties
    ) {
        return new AggregationExecutor(
                repository,
                properties.resolvedInstanceId(),
                properties.resolvedBatchSize(),
                properties.resolvedMaxCycles(),
                properties.resolvedStaleExecutionTimeout()
        );
    }

    /** 创建与常规聚合共用数据库锁和仓储的版本化显式重算执行器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.aggregation",
            name = "recompute-enabled",
            havingValue = "true")
    public AggregationRecomputeExecutor aggregationRecomputeExecutor(
            AggregationExecutionRepository repository,
            AggregationProperties properties
    ) {
        return new AggregationRecomputeExecutor(
                repository, properties.resolvedInstanceId());
    }

    /** 创建复用外部服务令牌访问控制面租约接口的内部客户端。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.aggregation",
            name = "recompute-enabled",
            havingValue = "true")
    public AggregationRecomputeClient aggregationRecomputeClient(
            StrategyDistributionProperties properties
    ) {
        return new HttpAggregationRecomputeClient(properties);
    }

    /** 创建每轮最多消费一个控制面租约的显式重算工作器。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mine.telemetry.aggregation",
            name = "recompute-enabled",
            havingValue = "true")
    public AggregationRecomputeWorker aggregationRecomputeWorker(
            AggregationRecomputeClient client,
            AggregationRecomputeExecutor executor,
            AggregationMetrics metrics,
            AggregationProperties properties
    ) {
        return new AggregationRecomputeWorker(
                client, executor, metrics, properties.resolvedInstanceId(),
                properties.resolvedRecomputeLeaseDuration());
    }

    /** 创建固定状态标签的数值聚合指标。 */
    @Bean
    public AggregationMetrics aggregationMetrics(MeterRegistry registry) {
        return new AggregationMetrics(registry);
    }

    /** 创建只运行数值聚合任务的单线程调度器。 */
    @Bean(name = "aggregationTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler aggregationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("telemetry-aggregation-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    /** 创建应用启动后注册固定延迟任务并可在关闭时取消的协调器。 */
    @Bean(destroyMethod = "close")
    public AggregationScheduler aggregationScheduler(
            AggregationExecutor executor,
            AggregationMetrics metrics,
            ObjectProvider<AggregationRecomputeWorker> recomputeWorker,
            @Qualifier("aggregationTaskScheduler") ThreadPoolTaskScheduler taskScheduler,
            AggregationProperties properties
    ) {
        return new AggregationScheduler(
                executor,
                metrics,
                recomputeWorker.getIfAvailable(),
                taskScheduler,
                properties.resolvedInitialDelay(),
                properties.resolvedInterval()
        );
    }
}
