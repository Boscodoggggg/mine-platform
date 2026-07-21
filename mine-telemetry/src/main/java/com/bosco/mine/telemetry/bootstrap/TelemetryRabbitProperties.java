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
 * @param location            定位消费、Outbox 和缓存恢复参数
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
        Outbox outbox,
        Location location) {

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
        return outbox == null
                ? new Outbox(null, null, null, null, null, null, null, null, null)
                : outbox;
    }

    /** @return 定位独立消费与恢复参数，未配置时使用安全默认值 */
    public Location resolvedLocation() {
        return location == null
                ? new Location(null, null, null, null, null, null)
                : location;
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
     * @param dispatchConcurrency Outbox 发布最大并发数
     * @param dispatchQueueCapacity Outbox 精确唤醒有界队列容量
     * @param recoveryInterval 持久待发布事件周期恢复间隔
     */
    public record Outbox(
            String instanceId,
            Duration leaseDuration,
            Integer maxAttempts,
            Integer recoveryLimit,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            Integer dispatchConcurrency,
            Integer dispatchQueueCapacity,
            Duration recoveryInterval
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

        /** @return Outbox 发布最大并发数 */
        public int resolvedDispatchConcurrency() {
            return dispatchConcurrency == null ? 4 : dispatchConcurrency;
        }

        /** @return Outbox 精确唤醒有界队列容量 */
        public int resolvedDispatchQueueCapacity() {
            return dispatchQueueCapacity == null ? 1_000 : dispatchQueueCapacity;
        }

        /** @return 持久待发布事件周期恢复间隔 */
        public Duration resolvedRecoveryInterval() {
            return recoveryInterval == null ? Duration.ofSeconds(10) : recoveryInterval;
        }
    }

    /**
     * 高频定位链路独立于告警的容量和恢复参数。
     *
     * @param prefetch 定位单消息消费者预取数量
     * @param outbox 位置实时 Outbox 租约与退避参数
     * @param cacheRecoveryInterval Redis 当前位置周期恢复间隔
     * @param cacheRecoverySiteLimit 单次最多恢复矿区数
     * @param cacheRecoveryTagLimit 每矿单次最多恢复标签数
     * @param spatial 空间评估、事件 Outbox 和后台运行参数
     */
    public record Location(
            Integer prefetch,
            Outbox outbox,
            Duration cacheRecoveryInterval,
            Integer cacheRecoverySiteLimit,
            Integer cacheRecoveryTagLimit,
            Spatial spatial
    ) {
        /** @return 定位消费者预取数量 */
        public int resolvedPrefetch() {
            return prefetch == null ? 100 : prefetch;
        }

        /** @return 位置实时 Outbox 参数 */
        public Outbox resolvedOutbox() {
            return outbox == null
                    ? new Outbox(
                    "mine-telemetry-location", null, null, 2_000,
                    null, null, 4, 10_000, null)
                    : outbox;
        }

        /** @return Redis 当前位置周期恢复间隔 */
        public Duration resolvedCacheRecoveryInterval() {
            return cacheRecoveryInterval == null
                    ? Duration.ofMinutes(5) : cacheRecoveryInterval;
        }

        /** @return 单次最多恢复矿区数 */
        public int resolvedCacheRecoverySiteLimit() {
            return cacheRecoverySiteLimit == null ? 100 : cacheRecoverySiteLimit;
        }

        /** @return 每矿单次最多恢复标签数 */
        public int resolvedCacheRecoveryTagLimit() {
            return cacheRecoveryTagLimit == null ? 10_000 : cacheRecoveryTagLimit;
        }

        /** @return 空间事件运行参数 */
        public Spatial resolvedSpatial() {
            return spatial == null
                    ? new Spatial(null, null, null, null)
                    : spatial;
        }
    }

    /**
     * 定位事实的事件时间评估和空间事件可靠发布参数。
     *
     * @param enabled 是否启用空间评估和事件发布后台任务
     * @param evaluationInterval 空间评估轮询周期
     * @param evaluationBatchSize 单轮空间评估最大事实数
     * @param outbox 空间事件 Outbox 租约、退避和恢复参数
     */
    public record Spatial(
            Boolean enabled,
            Duration evaluationInterval,
            Integer evaluationBatchSize,
            Outbox outbox
    ) {
        /** @return 未显式关闭时启用空间评估和事件发布 */
        public boolean resolvedEnabled() {
            return !Boolean.FALSE.equals(enabled);
        }

        /** @return 空间评估轮询周期 */
        public Duration resolvedEvaluationInterval() {
            return evaluationInterval == null ? Duration.ofSeconds(1) : evaluationInterval;
        }

        /** @return 单轮空间评估最大事实数 */
        public int resolvedEvaluationBatchSize() {
            return evaluationBatchSize == null ? 500 : evaluationBatchSize;
        }

        /** @return 空间事件 Outbox 参数 */
        public Outbox resolvedOutbox() {
            return outbox == null
                    ? new Outbox(
                    "mine-telemetry-spatial", null, null, 2_000,
                    null, null, 2, 5_000, null)
                    : outbox;
        }
    }
}
