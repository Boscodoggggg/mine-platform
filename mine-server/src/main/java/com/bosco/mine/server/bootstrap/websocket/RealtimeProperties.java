package com.bosco.mine.server.bootstrap.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 统一实时通道配置。
 *
 * @param enabled 实时通道是否启用
 * @param path WebSocket 访问路径
 * @param instanceId 服务实例标识，用于生成本实例独占的 RabbitMQ 队列
 * @param maxSubscriptions 单连接最大订阅数
 * @param maxDevices 单连接最大设备数
 * @param maxProperties 单连接最大属性数
 * @param maxMessageBytes 单条客户端消息最大字节数
 * @param authorizationTtl 服务端推送可使用的授权快照最长有效期
 * @param rabbitEnabled RabbitMQ 实时事件桥接是否启用
 * @param rabbitHost RabbitMQ 主机名
 * @param rabbitPort RabbitMQ AMQP 端口
 * @param rabbitAutoStartup RabbitMQ 监听容器是否随应用启动
 * @param rabbitPrefetch RabbitMQ 单消费者预取数
 * @param account RabbitMQ 服务账号
 */
@ConfigurationProperties("mine.realtime")
public record RealtimeProperties(
        Boolean enabled,
        String path,
        String instanceId,
        Integer maxSubscriptions,
        Integer maxDevices,
        Integer maxProperties,
        Integer maxMessageBytes,
        Duration authorizationTtl,
        Boolean rabbitEnabled,
        String rabbitHost,
        Integer rabbitPort,
        Boolean rabbitAutoStartup,
        Integer rabbitPrefetch,
        Account account
) {

    private static final Pattern INSTANCE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * 返回实时通道是否启用。
     *
     * @return 是否启用
     */
    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 返回标准化的 WebSocket 路径。
     *
     * @return WebSocket 路径
     */
    public String resolvedPath() {
        String value = path == null || path.isBlank() ? "/api/v1/realtime" : path.strip();
        if (!value.startsWith("/")) {
            throw new IllegalArgumentException("path 必须以 / 开头");
        }
        return value;
    }

    /**
     * 返回本实例独占、自动删除的实时队列名称。
     *
     * @return RabbitMQ 队列名称
     */
    public String resolvedQueueName() {
        String value = instanceId == null ? "" : instanceId.strip();
        if (!INSTANCE_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("instanceId 只能包含字母、数字、下划线和短横线，长度为 1 至 64");
        }
        return "mine.realtime.server." + value + ".q";
    }

    /**
     * 返回单连接资源限制。
     *
     * @return 实时连接限制
     */
    public RealtimeLimits resolvedLimits() {
        return new RealtimeLimits(
                positiveOrDefault(maxSubscriptions, 64, "maxSubscriptions"),
                positiveOrDefault(maxDevices, 128, "maxDevices"),
                positiveOrDefault(maxProperties, 1024, "maxProperties"),
                positiveOrDefault(maxMessageBytes, 65_536, "maxMessageBytes")
        );
    }

    /**
     * 返回服务端推送可使用的授权快照最长有效期。
     *
     * @return 授权快照有效期
     */
    public Duration resolvedAuthorizationTtl() {
        Duration resolved = authorizationTtl == null
                ? Duration.ofSeconds(30) : authorizationTtl;
        if (resolved.compareTo(Duration.ofSeconds(30)) < 0
                || resolved.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("authorizationTtl 必须介于 PT30S 和 PT5M 之间");
        }
        return resolved;
    }

    /**
     * 返回 RabbitMQ 实时事件桥接是否启用。
     *
     * @return 是否启用 RabbitMQ 桥接
     */
    public boolean resolvedRabbitEnabled() {
        return Boolean.TRUE.equals(rabbitEnabled);
    }

    /**
     * 返回 RabbitMQ 主机名。
     *
     * @return RabbitMQ 主机名
     */
    public String resolvedRabbitHost() {
        return rabbitHost == null || rabbitHost.isBlank() ? "rabbitmq" : rabbitHost.strip();
    }

    /**
     * 返回 RabbitMQ AMQP 端口。
     *
     * @return AMQP 端口
     */
    public int resolvedRabbitPort() {
        return positiveOrDefault(rabbitPort, 5672, "rabbitPort");
    }

    /**
     * 返回 RabbitMQ 监听容器是否自动启动。
     *
     * @return 是否自动启动
     */
    public boolean resolvedRabbitAutoStartup() {
        return rabbitAutoStartup == null || rabbitAutoStartup;
    }

    /**
     * 返回 RabbitMQ 消费预取数。
     *
     * @return 预取数
     */
    public int resolvedPrefetch() {
        return positiveOrDefault(rabbitPrefetch, 500, "rabbitPrefetch");
    }

    private int positiveOrDefault(Integer value, int defaultValue, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < 1) {
            throw new IllegalArgumentException(field + " 必须大于 0");
        }
        return resolved;
    }

    /**
     * RabbitMQ 实时消费服务账号。
     *
     * @param username 用户名
     * @param passwordFile 密码密钥文件
     */
    public record Account(String username, Path passwordFile) {
    }
}
