package com.bosco.mine.server.modules.alarm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 管理服务告警 RabbitMQ 消费连接参数。
 *
 * @param enabled 是否启用告警消费
 * @param autoStartup 监听容器是否随应用启动
 * @param host RabbitMQ 主机
 * @param port RabbitMQ AMQP 端口
 * @param prefetch 单消费者预取数
 * @param account 告警只读消费账号
 */
@ConfigurationProperties("mine.alarm.rabbit")
public record AlarmRabbitProperties(
        Boolean enabled,
        Boolean autoStartup,
        String host,
        Integer port,
        Integer prefetch,
        Account account
) {

    /** 返回 RabbitMQ 主机。 */
    public String resolvedHost() {
        return host == null || host.isBlank() ? "rabbitmq" : host.strip();
    }

    /** 返回 RabbitMQ AMQP 端口。 */
    public int resolvedPort() {
        return port == null ? 5672 : port;
    }

    /** 返回监听容器自动启动开关。 */
    public boolean resolvedAutoStartup() {
        return autoStartup == null || autoStartup;
    }

    /** 返回单消费者预取数。 */
    public int resolvedPrefetch() {
        return prefetch == null ? 50 : prefetch;
    }

    /**
     * 告警事实只读消费账号。
     *
     * @param username RabbitMQ 用户名
     * @param passwordFile 只读密码文件
     */
    public record Account(String username, Path passwordFile) {
    }
}
