package com.bosco.mine.gateway.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * gateway 双 RabbitMQ vhost 连接参数。
 *
 * @param autoStartup 是否随应用自动启动设备消费容器
 * @param host RabbitMQ 主机
 * @param port AMQP 端口
 * @param maxPayloadBytes 单条原始设备报文最大字节数
 * @param confirmTimeoutMillis 平台事件发布确认超时毫秒数
 * @param prefetch 设备上行消费者预取数量
 * @param identitySnapshotFile 设备凭据身份快照文件
 * @param device 设备 vhost 只读账号
 * @param platform 平台 vhost 只写账号
 */
@ConfigurationProperties("mine.gateway.rabbit")
public record GatewayRabbitProperties(
        Boolean autoStartup,
        String host,
        Integer port,
        Integer maxPayloadBytes,
        Long confirmTimeoutMillis,
        Integer prefetch,
        Path identitySnapshotFile,
        Account device,
        Account platform
) {

    /**
     * @return 未显式关闭时自动启动
     */
    public boolean resolvedAutoStartup() {
        return !Boolean.FALSE.equals(autoStartup);
    }

    /**
     * @return RabbitMQ 主机，默认使用 Compose 服务名
     */
    public String resolvedHost() {
        return host == null || host.isBlank() ? "rabbitmq" : host;
    }

    /**
     * @return AMQP 端口
     */
    public int resolvedPort() {
        return port == null ? 5672 : port;
    }

    /**
     * @return 单条报文最大字节数
     */
    public int resolvedMaxPayloadBytes() {
        return maxPayloadBytes == null ? 64 * 1024 : maxPayloadBytes;
    }

    /**
     * @return 发布确认超时毫秒数
     */
    public long resolvedConfirmTimeoutMillis() {
        return confirmTimeoutMillis == null ? 5000 : confirmTimeoutMillis;
    }

    /**
     * @return 消费者预取数量
     */
    public int resolvedPrefetch() {
        return prefetch == null ? 50 : prefetch;
    }

    /**
     * @return 设备凭据身份快照文件
     */
    public Path resolvedIdentitySnapshotFile() {
        return identitySnapshotFile == null
                ? Path.of("/etc/mine/gateway/device-identities.json")
                : identitySnapshotFile;
    }

    /**
     * RabbitMQ 服务账号和外部密码文件。
     *
     * @param username 服务账号
     * @param passwordFile 密码文件
     */
    public record Account(String username, Path passwordFile) {
    }
}
