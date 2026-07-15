package com.bosco.mine.server.modules.command.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 命令 Outbox 可靠发布配置。
 *
 * @param enabled 是否启用可靠发布
 * @param instanceId 服务实例标识
 * @param rabbitHost RabbitMQ 主机
 * @param rabbitPort RabbitMQ AMQP 端口
 * @param confirmTimeout 发布确认超时
 * @param leaseDuration 事件认领租约
 * @param maxAttempts 最大尝试次数
 * @param recoveryLimit 启动或重连单次恢复上限
 * @param retryBaseDelay 重试基础间隔
 * @param retryMaxDelay 重试最大间隔
 * @param account RabbitMQ 发布账号
 */
@ConfigurationProperties("mine.command-outbox")
public record CommandOutboxProperties(
        Boolean enabled,
        String instanceId,
        String rabbitHost,
        Integer rabbitPort,
        Duration confirmTimeout,
        Duration leaseDuration,
        Integer maxAttempts,
        Integer recoveryLimit,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        Account account
) {
    private static final Pattern INSTANCE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** @return 标准化实例标识 */
    public String resolvedInstanceId() {
        String value = instanceId == null ? "" : instanceId.strip();
        if (!INSTANCE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("命令 Outbox 实例标识格式不合法");
        }
        return value;
    }

    /** @return RabbitMQ 主机 */
    public String resolvedRabbitHost() {
        return rabbitHost == null || rabbitHost.isBlank() ? "rabbitmq" : rabbitHost.strip();
    }

    /** @return RabbitMQ AMQP 端口 */
    public int resolvedRabbitPort() {
        return positive(rabbitPort, 5672, "RabbitMQ 端口");
    }

    /** @return 发布确认超时 */
    public Duration resolvedConfirmTimeout() {
        return positive(confirmTimeout, Duration.ofSeconds(5), "发布确认超时");
    }

    /** @return 认领租约 */
    public Duration resolvedLeaseDuration() {
        return positive(leaseDuration, Duration.ofSeconds(30), "认领租约");
    }

    /** @return 最大尝试次数 */
    public int resolvedMaxAttempts() {
        return positive(maxAttempts, 8, "最大尝试次数");
    }

    /** @return 单次恢复上限 */
    public int resolvedRecoveryLimit() {
        return positive(recoveryLimit, 500, "单次恢复上限");
    }

    /** @return 重试基础间隔 */
    public Duration resolvedRetryBaseDelay() {
        return positive(retryBaseDelay, Duration.ofSeconds(1), "重试基础间隔");
    }

    /** @return 重试最大间隔 */
    public Duration resolvedRetryMaxDelay() {
        Duration value = positive(retryMaxDelay, Duration.ofMinutes(1), "重试最大间隔");
        if (value.compareTo(resolvedRetryBaseDelay()) < 0) {
            throw new IllegalArgumentException("重试最大间隔不得小于基础间隔");
        }
        return value;
    }

    private int positive(Integer value, int fallback, String label) {
        int resolved = value == null ? fallback : value;
        if (resolved < 1) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return resolved;
    }

    private Duration positive(Duration value, Duration fallback, String label) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return resolved;
    }

    /**
     * RabbitMQ 命令发布服务账号。
     *
     * @param username 用户名
     * @param passwordFile 密码密钥文件
     */
    public record Account(String username, Path passwordFile) {
    }
}
