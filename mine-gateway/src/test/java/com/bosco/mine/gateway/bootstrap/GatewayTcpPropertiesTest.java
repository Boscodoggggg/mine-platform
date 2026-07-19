package com.bosco.mine.gateway.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 TCP 监听器默认关闭、资源上限和 TLS secret 文件边界。
 */
class GatewayTcpPropertiesTest {

    @Test
    void 应提供保守默认值且应用默认关闭() {
        GatewayTcpProperties properties = properties(false, null, null, null, null, null);

        properties.validate();

        assertThat(properties.resolvedEnabled()).isFalse();
        assertThat(properties.resolvedBindHost()).isEqualTo("0.0.0.0");
        assertThat(properties.resolvedPort()).isEqualTo(9000);
        assertThat(properties.resolvedWorkerThreads()).isEqualTo(2);
        assertThat(properties.resolvedBusinessThreads()).isEqualTo(4);
        assertThat(properties.resolvedBacklog()).isEqualTo(1024);
        assertThat(properties.resolvedMaxConnections()).isEqualTo(10000);
        assertThat(properties.resolvedMaxConnectionsPerIp()).isEqualTo(100);
        assertThat(properties.resolvedReadIdleSeconds()).isEqualTo(120);
        assertThat(properties.resolvedShutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.resolvedTls().resolvedEnabled()).isFalse();
    }

    @Test
    void 启用监听器时应要求显式协议键并允许随机测试端口() {
        assertThatThrownBy(() -> properties(true, null, 0, null, null, null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protocol-key");

        GatewayTcpProperties enabled = properties(
                true, "127.0.0.1", 0, "haul-truck-binary", null, null);
        enabled.validate();
        assertThat(enabled.resolvedPort()).isZero();
        assertThat(enabled.resolvedProtocolKey()).isEqualTo("haul-truck-binary");
    }

    @Test
    void 应拒绝危险资源参数和不成对TLS文件() {
        assertThatThrownBy(() -> new GatewayTcpProperties(
                true, "127.0.0.1", 9000, "haul-truck-binary",
                0, 4, 1024, 10000, 100, 120, Duration.ofSeconds(30), null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker-threads");
        assertThatThrownBy(() -> new GatewayTcpProperties(
                true, "127.0.0.1", 9000, "haul-truck-binary",
                2, 4, 1024, 100, 101, 120, Duration.ofSeconds(30), null).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("per-ip");
        assertThatThrownBy(() -> properties(
                true, "127.0.0.1", 9000, "haul-truck-binary",
                null, new GatewayTcpProperties.Tls(true, Path.of("relative.crt"), null)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TLS");
    }

    @Test
    void 统一入口必须启用TLS且不能静态绑定插件() {
        assertThatThrownBy(() -> new GatewayTcpProperties(
                true, "127.0.0.1", 9000, null, GatewayTcpProperties.ListenerMode.UNIFIED,
                2, 4, 1024, 10000, 100, 120, Duration.ofSeconds(30),
                new GatewayTcpProperties.Tls(false, null, null)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TLS");

        assertThatThrownBy(() -> new GatewayTcpProperties(
                true, "127.0.0.1", 9000, "haul-truck-binary", GatewayTcpProperties.ListenerMode.UNIFIED,
                2, 4, 1024, 10000, 100, 120, Duration.ofSeconds(30),
                new GatewayTcpProperties.Tls(true, Path.of("/tmp/server.crt"), Path.of("/tmp/server.key"))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能静态配置");
    }

    private GatewayTcpProperties properties(
            Boolean enabled,
            String bindHost,
            Integer port,
            String protocolKey,
            Integer workerThreads,
            GatewayTcpProperties.Tls tls
    ) {
        return new GatewayTcpProperties(
                enabled, bindHost, port, protocolKey, workerThreads,
                null, null, null, null, null, null, tls
        );
    }
}
