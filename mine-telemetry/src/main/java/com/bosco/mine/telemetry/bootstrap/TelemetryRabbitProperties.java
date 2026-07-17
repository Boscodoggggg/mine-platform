package com.bosco.mine.telemetry.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

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
 * @param outbox              告警 Outbox 租约、重试与恢复参数
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
        Account account,
        Outbox outbox) {

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

    /** @return 告警 Outbox 参数，未配置时使用安全默认值 */
    public Outbox resolvedOutbox() {
        return outbox == null ? new Outbox(null, null, null, null, null, null) : outbox;
    }

    /**
     * RabbitMQ 服务账号和外部密码文件。
     *
     * @param username     服务账号
     * @param passwordFile 密码文件
     */
    public record Account(String username, Path passwordFile) {
    }

    /**
     * 告警 Outbox 认领与失败恢复参数。
     *
     * @param instanceId 当前服务实例标识
     * @param leaseDuration 发布认领租约
     * @param maxAttempts 最大发布尝试次数
     * @param recoveryLimit 单次启动或重连恢复上限
     * @param retryBaseDelay 指数退避基础间隔
     * @param retryMaxDelay 指数退避最大间隔
     */
    public record Outbox(
            String instanceId,
            Duration leaseDuration,
            Integer maxAttempts,
            Integer recoveryLimit,
            Duration retryBaseDelay,
            Duration retryMaxDelay
    ) {
        /** @return 非空服务实例标识 */
        public String resolvedInstanceId() {
            return instanceId == null || instanceId.isBlank() ? "mine-telemetry" : instanceId;
        }

        /** @return 发布认领租约 */
        public Duration resolvedLeaseDuration() {
            return leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        }

        /** @return 最大发布尝试次数 */
        public int resolvedMaxAttempts() {
            return maxAttempts == null ? 8 : maxAttempts;
        }

        /** @return 单次有界恢复数量 */
        public int resolvedRecoveryLimit() {
            return recoveryLimit == null ? 500 : recoveryLimit;
        }

        /** @return 指数退避基础间隔 */
        public Duration resolvedRetryBaseDelay() {
            return retryBaseDelay == null ? Duration.ofSeconds(1) : retryBaseDelay;
        }

        /** @return 指数退避最大间隔 */
        public Duration resolvedRetryMaxDelay() {
            return retryMaxDelay == null ? Duration.ofMinutes(5) : retryMaxDelay;
        }
    }
}
