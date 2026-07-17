package com.bosco.mine.gateway.tcp;

import com.bosco.mine.gateway.bootstrap.GatewayTcpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 TCP 监听器健康状态只暴露受控运行信息，不泄露设备或来源地址。
 */
class TcpGatewayHealthIndicatorTest {

    @Test
    void 监听器运行时应报告协议端口和TLS状态() {
        TcpGatewayServer server = mock(TcpGatewayServer.class);
        when(server.isRunning()).thenReturn(true);
        when(server.localPort()).thenReturn(9443);

        Health health = new TcpGatewayHealthIndicator(server, properties(true)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("protocol", "haul-truck-binary")
                .containsEntry("port", 9443)
                .containsEntry("tls", true)
                .doesNotContainKeys("deviceId", "remoteAddress");
    }

    @Test
    void 监听器停止时应报告未监听且不读取端口() {
        TcpGatewayServer server = mock(TcpGatewayServer.class);
        when(server.isRunning()).thenReturn(false);

        Health health = new TcpGatewayHealthIndicator(server, properties(false)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("protocol", "haul-truck-binary")
                .containsEntry("tls", false)
                .containsEntry("reason", "listener-not-running")
                .doesNotContainKey("port");
    }

    private GatewayTcpProperties properties(boolean tlsEnabled) {
        return new GatewayTcpProperties(
                true, "0.0.0.0", 9000, "haul-truck-binary",
                1, 1, 64, 10, 2, 5, Duration.ofSeconds(5),
                new GatewayTcpProperties.Tls(tlsEnabled, null, null)
        );
    }
}
