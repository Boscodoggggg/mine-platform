package com.bosco.mine.server.modules.device.repository;

import com.bosco.mine.server.modules.device.infrastructure.TelemetryQueryConnection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证遥测查询仓储能够由 Spring 容器完成构造器注入。
 */
class RedisTimescaleDeviceTelemetryRepositoryContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("mine.telemetry-query.enabled=true")
            .withUserConfiguration(RepositoryTestConfiguration.class);

    /**
     * 多个构造器并存时，生产构造器必须能够被 Spring 明确选中。
     */
    @Test
    void shouldCreateRepositoryWithManagedTelemetryConnection() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(RedisTimescaleDeviceTelemetryRepository.class)
        );
    }

    /**
     * 仓储构造器注入测试配置。
     */
    @Configuration(proxyBeanMethods = false)
    @Import(RedisTimescaleDeviceTelemetryRepository.class)
    static class RepositoryTestConfiguration {

        /**
         * 提供 Redis 字符串操作模板替身。
         *
         * @return Redis 字符串操作模板替身
         */
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        /**
         * 提供受管遥测查询连接替身。
         *
         * @return 受管遥测查询连接替身
         */
        @Bean
        TelemetryQueryConnection telemetryQueryConnection() {
            TelemetryQueryConnection connection = mock(TelemetryQueryConnection.class);
            when(connection.jdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
            when(connection.readOnlyTransaction()).thenReturn(mock(TransactionTemplate.class));
            return connection;
        }
    }
}
