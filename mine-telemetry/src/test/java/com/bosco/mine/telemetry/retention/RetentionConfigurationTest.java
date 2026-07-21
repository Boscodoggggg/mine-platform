package com.bosco.mine.telemetry.retention;

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
 * 校验数据保留默认关闭、安全配置边界和独立单线程装配。
 */
class RetentionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RetentionConfiguration.class, TestDependencies.class);

    @Test
    void 未配置时应保持关闭并使用安全默认参数() {
        RetentionProperties properties = new RetentionProperties(
                null, null, null, null, null, null, null);

        assertThat(properties.resolvedEnabled()).isFalse();
        assertThat(properties.resolvedInstanceId()).isEqualTo("mine-telemetry");
        assertThat(properties.resolvedBatchSize()).isEqualTo(10_000);
        assertThat(properties.resolvedMaxCycles()).isEqualTo(10);
        assertThat(properties.resolvedInitialDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.resolvedInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.resolvedStaleExecutionTimeout()).isEqualTo(Duration.ofHours(2));

        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(RetentionScheduler.class);
            assertThat(context).doesNotHaveBean(RetentionExecutionRepository.class);
        });
    }

    @Test
    void 应拒绝可能失控或掩盖停滞的参数() {
        assertThatThrownBy(() -> properties(0, 10, Duration.ofMinutes(5))
                .resolvedBatchSize()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(100_001, 10, Duration.ofMinutes(5))
                .resolvedBatchSize()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(10_000, 0, Duration.ofMinutes(5))
                .resolvedMaxCycles()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(10_000, 1_001, Duration.ofMinutes(5))
                .resolvedMaxCycles()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(10_000, 10, Duration.ofSeconds(9))
                .resolvedInterval()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(10_000, 10, Duration.ofHours(25))
                .resolvedInterval()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionProperties(
                true, " ", 10_000, 10, Duration.ZERO,
                Duration.ofMinutes(5), Duration.ofHours(2)).resolvedInstanceId())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionProperties(
                true, "telemetry", 10_000, 10, Duration.ofHours(25),
                Duration.ofMinutes(5), Duration.ofHours(2)).resolvedInitialDelay())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionProperties(
                true, "telemetry", 10_000, 10, Duration.ZERO,
                Duration.ofMinutes(5), Duration.ofSeconds(59))
                .resolvedStaleExecutionTimeout()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 显式开启时应装配单线程调度和全部运行组件() {
        contextRunner.withPropertyValues(
                "mine.telemetry.retention.enabled=true",
                "mine.telemetry.retention.instance-id=telemetry-it"
        ).run(context -> {
            assertThat(context).hasSingleBean(RetentionScheduler.class);
            assertThat(context).hasSingleBean(RetentionMetrics.class);
            assertThat(context).hasSingleBean(RetentionExecutor.class);
            assertThat(context).hasSingleBean(RetentionExecutionRepository.class);
            ThreadPoolTaskScheduler scheduler = context.getBean(
                    "retentionTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("telemetry-retention-");
        });
    }

    private RetentionProperties properties(int batchSize, int maxCycles, Duration interval) {
        return new RetentionProperties(
                true, "telemetry", batchSize, maxCycles, Duration.ZERO,
                interval, Duration.ofHours(2));
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
