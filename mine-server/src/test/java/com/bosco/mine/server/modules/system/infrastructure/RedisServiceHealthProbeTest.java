package com.bosco.mine.server.modules.system.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验 Redis 运行探针会直接执行连接 PING，并始终关闭临时连接。
 */
class RedisServiceHealthProbeTest {

    @Test
    void Redis响应Pong时应返回正常并关闭连接() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        ServiceHealthProbe.ProbeResult result = new RedisServiceHealthProbe(connectionFactory).probe();

        assertThat(result.key()).isEqualTo("redis");
        assertThat(result.category()).isEqualTo("STORAGE");
        assertThat(result.status()).isEqualTo("UP");
        assertThat(result.summary()).isEqualTo("connectionUp");
        verify(connection).close();
    }

    @Test
    void Redis连接失败时应返回异常状态且不泄露连接信息() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(connectionFactory.getConnection()).thenThrow(
                new RedisConnectionFailureException("redis://secret-host:6379")
        );

        ServiceHealthProbe.ProbeResult result = new RedisServiceHealthProbe(connectionFactory).probe();

        assertThat(result.status()).isEqualTo("DOWN");
        assertThat(result.summary()).isEqualTo("connectionDown");
        assertThat(result.toString()).doesNotContain("secret-host");
    }

    @Test
    void Redis执行Ping失败时仍应关闭已取得的连接() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenThrow(new RedisConnectionFailureException("连接已断开"));

        ServiceHealthProbe.ProbeResult result = new RedisServiceHealthProbe(connectionFactory).probe();

        assertThat(result.status()).isEqualTo("DOWN");
        assertThat(result.summary()).isEqualTo("connectionDown");
        verify(connection).close();
    }
}
