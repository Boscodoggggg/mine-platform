package com.bosco.mine.server.bootstrap.websocket;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验实时通道默认限额和多实例独立队列命名。
 */
class RealtimePropertiesTest {

    @Test
    void 默认值应适合单矿区多实例部署() {
        RealtimeProperties properties = new RealtimeProperties(
                null, null, "server-a", null, null, null, null,
                null, null, null, null, null, null,
                new RealtimeProperties.Account("mine-server-realtime", Path.of("/tmp/password"))
        );

        assertThat(properties.resolvedPath()).isEqualTo("/api/v1/realtime");
        assertThat(properties.resolvedQueueName())
                .isEqualTo("mine.realtime.server.server-a.q");
        assertThat(properties.resolvedLimits())
                .isEqualTo(new RealtimeLimits(64, 128, 1024, 65_536));
        assertThat(properties.resolvedPrefetch()).isEqualTo(500);
        assertThat(properties.resolvedAuthorizationTtl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void 实例标识不得包含RabbitMQ资源名之外的字符() {
        RealtimeProperties properties = new RealtimeProperties(
                true, null, "server/a", null, null, null, null,
                null, null, null, null, null, null,
                new RealtimeProperties.Account("mine-server-realtime", Path.of("/tmp/password"))
        );

        assertThatThrownBy(properties::resolvedQueueName)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instanceId");
    }

    @Test
    void 授权快照有效期必须限制在安全范围内() {
        RealtimeProperties tooShort = new RealtimeProperties(
                true, null, "server-a", null, null, null, null,
                Duration.ofSeconds(29), null, null, null, null, null,
                new RealtimeProperties.Account("unused", Path.of("/tmp/unused"))
        );
        RealtimeProperties tooLong = new RealtimeProperties(
                true, null, "server-a", null, null, null, null,
                Duration.ofMinutes(6), null, null, null, null, null,
                new RealtimeProperties.Account("unused", Path.of("/tmp/unused"))
        );

        assertThatThrownBy(tooShort::resolvedAuthorizationTtl)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorizationTtl");
        assertThatThrownBy(tooLong::resolvedAuthorizationTtl)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorizationTtl");
    }
}
