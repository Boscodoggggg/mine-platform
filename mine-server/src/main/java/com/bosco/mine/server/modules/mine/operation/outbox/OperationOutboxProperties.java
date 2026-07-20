package com.bosco.mine.server.modules.mine.operation.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 生产运营 Outbox 可靠发布配置。
 *
 * @param enabled 是否启用运营实时事件发布
 * @param rabbitHost RabbitMQ 主机
 * @param rabbitPort RabbitMQ AMQP 端口
 * @param confirmTimeout 发布确认超时
 * @param leaseDuration 单次认领租约
 * @param maxAttempts 最大发布尝试次数
 * @param recoveryLimit 单次恢复事件上限
 * @param retryBaseDelay 重试基础间隔
 * @param retryMaxDelay 重试最大间隔
 * @param recoveryInterval 持久待发布事件恢复间隔
 * @param account 最小权限 RabbitMQ 发布账号
 */
@ConfigurationProperties("mine.operation-outbox")
public record OperationOutboxProperties(
        Boolean enabled,
        String rabbitHost,
        Integer rabbitPort,
        Duration confirmTimeout,
        Duration leaseDuration,
        Integer maxAttempts,
        Integer recoveryLimit,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        Duration recoveryInterval,
        Account account
) {

    /** @return RabbitMQ 主机 */
    public String resolvedRabbitHost() {
        return rabbitHost == null || rabbitHost.isBlank() ? "rabbitmq" : rabbitHost.strip();
    }

    /** @return RabbitMQ AMQP 端口 */
    public int resolvedRabbitPort() {
        return bounded(rabbitPort, 5672, 1, 65_535, "RabbitMQ 端口");
    }

    /** @return 发布确认超时 */
    public Duration resolvedConfirmTimeout() {
        return bounded(
                confirmTimeout, Duration.ofSeconds(5),
                Duration.ofMillis(100), Duration.ofMinutes(1), "发布确认超时");
    }

    /** @return 单次认领租约 */
    public Duration resolvedLeaseDuration() {
        return bounded(
                leaseDuration, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMinutes(10), "认领租约");
    }

    /** @return 最大发布尝试次数 */
    public int resolvedMaxAttempts() {
        return bounded(maxAttempts, 8, 1, 100, "最大尝试次数");
    }

    /** @return 单次恢复事件上限 */
    public int resolvedRecoveryLimit() {
        return bounded(recoveryLimit, 500, 1, 10_000, "单次恢复上限");
    }

    /** @return 重试基础间隔 */
    public Duration resolvedRetryBaseDelay() {
        return bounded(
                retryBaseDelay, Duration.ofSeconds(1),
                Duration.ofMillis(100), Duration.ofMinutes(10), "重试基础间隔");
    }

    /** @return 重试最大间隔 */
    public Duration resolvedRetryMaxDelay() {
        Duration value = bounded(
                retryMaxDelay, Duration.ofMinutes(1),
                Duration.ofMillis(100), Duration.ofHours(1), "重试最大间隔");
        if (value.compareTo(resolvedRetryBaseDelay()) < 0) {
            throw new IllegalArgumentException("重试最大间隔不得小于基础间隔");
        }
        return value;
    }

    /** @return 持久待发布事件恢复间隔 */
    public Duration resolvedRecoveryInterval() {
        return bounded(
                recoveryInterval, Duration.ofSeconds(1),
                Duration.ofMillis(100), Duration.ofMinutes(1), "恢复间隔");
    }

    private int bounded(Integer value, int fallback, int minimum, int maximum, String label) {
        int resolved = value == null ? fallback : value;
        if (resolved < minimum || resolved > maximum) {
            throw new IllegalArgumentException(label + "超出允许范围");
        }
        return resolved;
    }

    private Duration bounded(
            Duration value,
            Duration fallback,
            Duration minimum,
            Duration maximum,
            String label
    ) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.compareTo(minimum) < 0 || resolved.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + "超出允许范围");
        }
        return resolved;
    }

    /**
     * RabbitMQ 运营事件发布账号。
     *
     * @param username 服务账号用户名
     * @param passwordFile 只读密码密钥文件
     */
    public record Account(String username, Path passwordFile) {
    }
}
