package com.bosco.mine.telemetry.location;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.model.location.LocationObservation;

import java.time.Duration;
import java.util.Objects;

/**
 * 使用固定、低基数原因评估可存储定位事实的时间和水平精度质量。
 */
public final class LocationQualityEvaluator {

    private final Duration maximumAge;
    private final Duration maximumFutureSkew;
    private final double maximumAccuracyMeters;

    /** 使用五分钟时延、一分钟未来偏差和三十米精度阈值。 */
    public LocationQualityEvaluator() {
        this(Duration.ofMinutes(5), Duration.ofMinutes(1), 30D);
    }

    /**
     * 创建定位质量判定器。
     *
     * @param maximumAge 允许的最大接收时延
     * @param maximumFutureSkew 允许设备时钟领先接收时间的最大偏差
     * @param maximumAccuracyMeters 可判为正常的最大水平精度半径
     */
    public LocationQualityEvaluator(
            Duration maximumAge,
            Duration maximumFutureSkew,
            double maximumAccuracyMeters
    ) {
        this.maximumAge = requirePositive(maximumAge, "定位最大接收时延");
        this.maximumFutureSkew = requirePositive(
                maximumFutureSkew, "定位最大未来时钟偏差");
        if (!Double.isFinite(maximumAccuracyMeters) || maximumAccuracyMeters <= 0D) {
            throw new IllegalArgumentException("定位最大精度半径必须是正有限数值");
        }
        this.maximumAccuracyMeters = maximumAccuracyMeters;
    }

    /**
     * 按时间异常优先于精度异常的固定顺序评估观测。
     *
     * @param observation 已通过强类型契约校验的定位观测
     * @return 正常或带固定原因的降级结果
     */
    public Evaluation evaluate(LocationObservation observation) {
        Objects.requireNonNull(observation, "定位观测不能为空");
        if (observation.occurredAt().isAfter(
                observation.receivedAt().plus(maximumFutureSkew))) {
            return Evaluation.degraded("CLOCK_AHEAD");
        }
        if (observation.occurredAt().isBefore(
                observation.receivedAt().minus(maximumAge))) {
            return Evaluation.degraded("STALE_OBSERVATION");
        }
        if (observation.accuracyMeters() != null
                && observation.accuracyMeters() > maximumAccuracyMeters) {
            return Evaluation.degraded("LOW_ACCURACY");
        }
        return Evaluation.good();
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }

    /**
     * 可直接固化到历史事实的质量结果。
     *
     * @param status 正常或降级状态
     * @param reason 固定降级原因，正常时为空
     */
    public record Evaluation(
            RealtimeLocationEvent.QualityStatus status,
            String reason
    ) {

        /** 校验状态与原因的一致性。 */
        public Evaluation {
            Objects.requireNonNull(status, "定位质量状态不能为空");
            if ((status == RealtimeLocationEvent.QualityStatus.GOOD && reason != null)
                    || (status == RealtimeLocationEvent.QualityStatus.DEGRADED
                    && (reason == null || reason.isBlank()))) {
                throw new IllegalArgumentException("定位质量状态与原因不一致");
            }
        }

        /** @return 正常定位质量 */
        public static Evaluation good() {
            return new Evaluation(RealtimeLocationEvent.QualityStatus.GOOD, null);
        }

        /** @return 带固定原因的降级定位质量 */
        public static Evaluation degraded(String reason) {
            return new Evaluation(
                    RealtimeLocationEvent.QualityStatus.DEGRADED, reason);
        }
    }
}
