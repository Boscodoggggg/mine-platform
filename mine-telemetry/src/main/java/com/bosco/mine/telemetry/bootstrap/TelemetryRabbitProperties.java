package com.bosco.mine.telemetry.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 遥测服务访问平台 RabbitMQ vhost 的批消费参数。
 *
 * @param autoStartup         是否随应用自动启动消费容器
 * @param host                RabbitMQ 主机
 * @param port                AMQP 端口
 * @param prefetch            单消费者预取消息数
 * @param batchSize           单次交付给消费者的最大消息数
 * @param receiveTimeoutMillis 等待凑批的最大毫秒数
 * @param confirmTimeoutMillis 等待实时事件发布确认的最大毫秒数
 * @param account             平台 vhost 遥测服务最小权限账号
 */
@ConfigurationProperties("mine.telemetry.rabbit")
public record TelemetryRabbitProperties(
        Boolean autoStartup,
        String host,
        Integer port,
        Integer prefetch,
        Integer batchSize,
        Long receiveTimeoutMillis,
        Long confirmTimeoutMillis,
        Account account) {

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
     * @return 单消费者预取消息数
     */
    public int resolvedPrefetch() {
        return prefetch == null ? 200 : prefetch;
    }

    /**
     * @return 单批最大消息数
     */
    public int resolvedBatchSize() {
        return batchSize == null ? 100 : batchSize;
    }

    /**
     * @return 等待凑批的最大毫秒数
     */
    public long resolvedReceiveTimeoutMillis() {
        return receiveTimeoutMillis == null ? 1000L : receiveTimeoutMillis;
    }

    /**
     * @return 实时事件发布确认超时毫秒数
     */
    public long resolvedConfirmTimeoutMillis() {
        return confirmTimeoutMillis == null ? 5000L : confirmTimeoutMillis;
    }

    /**
     * RabbitMQ 服务账号和外部密码文件。
     *
     * @param username     服务账号
     * @param passwordFile 密码文件
     */
    public record Account(String username, Path passwordFile) {
    }
}
