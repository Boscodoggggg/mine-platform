package com.bosco.mine.gateway.tcp;

import com.bosco.mine.gateway.bootstrap.GatewayTcpProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

/**
 * 向 Actuator 提供 TCP 二进制监听器的受控健康状态。
 */
public final class TcpGatewayHealthIndicator implements HealthIndicator {

    private final TcpGatewayServer server;
    private final GatewayTcpProperties properties;

    /**
     * 创建 TCP 监听器健康指示器。
     *
     * @param server TCP 监听器
     * @param properties TCP 监听参数
     */
    public TcpGatewayHealthIndicator(
            TcpGatewayServer server,
            GatewayTcpProperties properties
    ) {
        this.server = Objects.requireNonNull(server, "TCP 监听器不能为空");
        this.properties = Objects.requireNonNull(properties, "TCP 参数不能为空");
    }

    /**
     * 返回监听器运行状态、协议键、实际端口和 TLS 开关。
     *
     * @return 不包含设备身份和来源地址的健康信息
     */
    @Override
    public Health health() {
        boolean running = server.isRunning();
        Health.Builder builder = running
                ? Health.up().withDetail("port", server.localPort())
                : Health.down().withDetail("reason", "listener-not-running");
        return builder
                .withDetail("protocol", properties.resolvedProtocolKey())
                .withDetail("tls", properties.resolvedTls().resolvedEnabled())
                .build();
    }
}
