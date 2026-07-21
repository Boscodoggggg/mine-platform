package com.bosco.mine.telemetry.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 数据保留任务的安全调度和有界删除参数。
 *
 * @param enabled 是否启用本实例的数据保留调度
 * @param instanceId 写入执行事实的服务实例标识
 * @param batchSize 单个数据集单批最大删除行数
 * @param maxCycles 单轮最多执行的双数据集批次周期
 * @param initialDelay 应用启动后的首次调度延迟
 * @param interval 每轮完成到下一轮开始的固定延迟
 * @param staleExecutionTimeout 运行事实失联后允许后续实例恢复的超时
 */
@ConfigurationProperties("mine.telemetry.retention")
public record RetentionProperties(
        Boolean enabled,
        String instanceId,
        Integer batchSize,
        Integer maxCycles,
        Duration initialDelay,
        Duration interval,
        Duration staleExecutionTimeout
) {

    /** @return 默认关闭，只有显式配置为真时才启用 */
    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** @return 非空且长度受控的实例标识 */
    public String resolvedInstanceId() {
        String resolved = instanceId == null ? "mine-telemetry" : instanceId.strip();
        if (resolved.isEmpty() || resolved.length() > 128) {
            throw new IllegalArgumentException("数据保留实例标识不能为空且长度不能超过 128");
        }
        return resolved;
    }

    /** @return 1 到 100000 之间的单批删除上限 */
    public int resolvedBatchSize() {
        return bounded(batchSize, 10_000, 1, 100_000, "数据保留批次大小");
    }

    /** @return 1 到 1000 之间的单轮周期上限 */
    public int resolvedMaxCycles() {
        return bounded(maxCycles, 10, 1, 1_000, "数据保留单轮周期");
    }

    /** @return 0 到 24 小时之间的首次调度延迟 */
    public Duration resolvedInitialDelay() {
        return bounded(
                initialDelay, Duration.ofSeconds(30), Duration.ZERO,
                Duration.ofHours(24), "数据保留首次调度延迟");
    }

    /** @return 10 秒到 24 小时之间的固定调度延迟 */
    public Duration resolvedInterval() {
        return bounded(
                interval, Duration.ofMinutes(5), Duration.ofSeconds(10),
                Duration.ofHours(24), "数据保留固定调度延迟");
    }

    /** @return 1 分钟到 7 天之间的运行事实失联超时 */
    public Duration resolvedStaleExecutionTimeout() {
        return bounded(
                staleExecutionTimeout, Duration.ofHours(2), Duration.ofMinutes(1),
                Duration.ofDays(7), "数据保留运行事实失联超时");
    }

    private int bounded(Integer value, int defaultValue, int minimum, int maximum, String label) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < minimum || resolved > maximum) {
            throw new IllegalArgumentException(
                    label + "必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return resolved;
    }

    private Duration bounded(
            Duration value,
            Duration defaultValue,
            Duration minimum,
            Duration maximum,
            String label
    ) {
        Duration resolved = value == null ? defaultValue : value;
        if (resolved.compareTo(minimum) < 0 || resolved.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + "超出安全范围");
        }
        return resolved;
    }
}
