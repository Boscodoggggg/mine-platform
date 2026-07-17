package com.bosco.mine.telemetry.aggregation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 校验数值聚合默认关闭、安全参数边界和独立单线程装配。
 */
class AggregationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AggregationConfiguration.class, TestDependencies.class);

    @Test
    void 未配置时应保持关闭并使用安全默认参数() {
        AggregationProperties properties = new AggregationProperties(
                null, null, null, null, null, null, null, null, null);

        assertThat(properties.resolvedEnabled()).isFalse();
        assertThat(properties.resolvedInstanceId()).isEqualTo("mine-telemetry");
        assertThat(properties.resolvedBatchSize()).isEqualTo(1_000);
        assertThat(properties.resolvedMaxCycles()).isEqualTo(10);
        assertThat(properties.resolvedInitialDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.resolvedInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.resolvedStaleExecutionTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.resolvedRecomputeEnabled()).isFalse();
        assertThat(properties.resolvedRecomputeLeaseDuration())
                .isEqualTo(Duration.ofMinutes(10));

        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AggregationScheduler.class);
            assertThat(context).doesNotHaveBean(AggregationExecutionRepository.class);
        });
    }

    @Test
    void 应拒绝可能导致失控或掩盖停滞的参数() {
        assertThatThrownBy(() -> properties(0, 10, Duration.ofMinutes(1))
                .resolvedBatchSize()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(10_001, 10, Duration.ofMinutes(1))
                .resolvedBatchSize()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(1_000, 0, Duration.ofMinutes(1))
                .resolvedMaxCycles()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(1_000, 1_001, Duration.ofMinutes(1))
                .resolvedMaxCycles()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(1_000, 10, Duration.ofSeconds(9))
                .resolvedInterval()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregationProperties(
                true, " ", 1_000, 10, Duration.ZERO,
                Duration.ofMinutes(1), Duration.ofHours(2), false, null)
                .resolvedInstanceId())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregationProperties(
                true, "telemetry", 1_000, 10, Duration.ZERO,
                Duration.ofMinutes(1), Duration.ofSeconds(59), false, null)
                .resolvedStaleExecutionTimeout()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AggregationProperties(
                true, "telemetry", 1_000, 10, Duration.ZERO,
                Duration.ofMinutes(1), Duration.ofHours(2), true, Duration.ofSeconds(59))
                .resolvedRecomputeLeaseDuration()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 显式开启时应装配单线程调度和全部运行组件() {
        contextRunner.withPropertyValues(
                "mine.telemetry.aggregation.enabled=true",
                "mine.telemetry.aggregation.instance-id=telemetry-aggregation-it"
        ).run(context -> {
            assertThat(context).hasSingleBean(AggregationScheduler.class);
            assertThat(context).hasSingleBean(AggregationMetrics.class);
            assertThat(context).hasSingleBean(AggregationExecutor.class);
            assertThat(context).hasSingleBean(AggregationExecutionRepository.class);
            assertThat(context).doesNotHaveBean(AggregationRecomputeWorker.class);
            ThreadPoolTaskScheduler scheduler = context.getBean(
                    "aggregationTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("telemetry-aggregation-");
        });
    }

    @Test
    void 显式开启重算消费时应装配内部客户端执行器和工作器() {
        contextRunner.withPropertyValues(
                "mine.telemetry.aggregation.enabled=true",
                "mine.telemetry.aggregation.recompute-enabled=true",
                "mine.telemetry.aggregation.instance-id=telemetry-aggregation-it",
                "mine.telemetry.strategy-distribution.server-url=http://mine-server:8080"
        ).run(context -> {
            assertThat(context).hasSingleBean(AggregationRecomputeClient.class);
            assertThat(context).hasSingleBean(AggregationRecomputeExecutor.class);
            assertThat(context).hasSingleBean(AggregationRecomputeWorker.class);
        });
    }

    private AggregationProperties properties(int batchSize, int maxCycles, Duration interval) {
        return new AggregationProperties(
                true, "telemetry", batchSize, maxCycles, Duration.ZERO,
                interval, Duration.ofHours(2), false, null);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        /** 创建无需真实连接的配置装配测试数据源。 */
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        /** 创建内存指标注册表。 */
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
