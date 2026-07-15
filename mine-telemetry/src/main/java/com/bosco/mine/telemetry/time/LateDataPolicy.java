package com.bosco.mine.telemetry.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 判定遥测消息的有效排序时间以及是否允许覆盖设备最新值。
 *
 * <p>设备原始发生时间始终由上游消息保留。本策略只决定排序时采用设备发生时间还是平台接收时间，
 * 不会静默修改原始时间。</p>
 */
public final class LateDataPolicy {

    private final Duration maximumFutureClockSkew;

    /**
     * 创建迟到数据策略。
     *
     * @param maximumFutureClockSkew 设备发生时间允许超前于平台接收时间的最大偏差
     */
    public LateDataPolicy(Duration maximumFutureClockSkew) {
        this.maximumFutureClockSkew = requireNonNegative(maximumFutureClockSkew);
    }

    /**
     * 根据设备发生时间、平台接收时间和已有最新时间生成排序判定。
     *
     * @param occurredAt       设备声明的发生时间
     * @param receivedAt       平台接收时间
     * @param latestEffectiveAt 当前设备已经生效的最新排序时间，可为空
     * @param deviceTimeTrusted 上游质量校验是否确认设备时间可信
     * @return 时间质量和最新值更新判定
     */
    public Decision decide(
            Instant occurredAt,
            Instant receivedAt,
            Instant latestEffectiveAt,
            boolean deviceTimeTrusted) {
        Objects.requireNonNull(occurredAt, "设备发生时间不能为空");
        Objects.requireNonNull(receivedAt, "平台接收时间不能为空");

        boolean excessiveFutureSkew = occurredAt.isAfter(receivedAt)
                && Duration.between(receivedAt, occurredAt).compareTo(maximumFutureClockSkew) > 0;
        boolean timeUntrusted = !deviceTimeTrusted || excessiveFutureSkew;
        Instant effectiveAt = timeUntrusted ? receivedAt : occurredAt;
        boolean late = latestEffectiveAt != null && effectiveAt.isBefore(latestEffectiveAt);
        boolean updateLatest = latestEffectiveAt == null || effectiveAt.isAfter(latestEffectiveAt);
        return new Decision(
                effectiveAt,
                timeUntrusted ? TimeQuality.UNTRUSTED : TimeQuality.TRUSTED,
                late,
                updateLatest
        );
    }

    private static Duration requireNonNegative(Duration duration) {
        Objects.requireNonNull(duration, "最大设备时钟偏差不能为空");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("最大设备时钟偏差不能为负数");
        }
        return duration;
    }

    /**
     * 时间排序判定结果。
     *
     * @param effectiveAt 参与最新值比较的有效时间
     * @param timeQuality 时间可信度
     * @param late        是否早于当前最新有效时间
     * @param updateLatest 是否允许覆盖当前最新值
     */
    public record Decision(
            Instant effectiveAt,
            TimeQuality timeQuality,
            boolean late,
            boolean updateLatest) {

        /**
         * 创建并校验时间排序判定结果。
         */
        public Decision {
            effectiveAt = Objects.requireNonNull(effectiveAt, "有效排序时间不能为空");
            timeQuality = Objects.requireNonNull(timeQuality, "时间可信度不能为空");
        }
    }

    /**
     * 设备时间可信度。
     */
    public enum TimeQuality {
        /** 设备发生时间处于允许的时钟偏差范围内。 */
        TRUSTED,
        /** 上游已判定设备时间不可信或发生时间明显超前，排序时改用平台接收时间。 */
        UNTRUSTED
    }
}
