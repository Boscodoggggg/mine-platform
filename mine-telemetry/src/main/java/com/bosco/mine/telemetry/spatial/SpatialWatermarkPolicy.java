package com.bosco.mine.telemetry.spatial;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 空间位置事实的事件时间水位策略，负责迟到边界和空闲刷新计算。
 */
public final class SpatialWatermarkPolicy {

    private final Duration allowedLateness;
    private final Duration idleFlushAfter;

    /**
     * 创建固定迟到容忍窗和空闲刷新阈值的水位策略。
     *
     * @param allowedLateness 允许迟到时长
     * @param idleFlushAfter  流量停止后刷新剩余事实的时长
     */
    public SpatialWatermarkPolicy(Duration allowedLateness, Duration idleFlushAfter) {
        this.allowedLateness = requireNonNegative(allowedLateness, "允许迟到时长");
        this.idleFlushAfter = requirePositive(idleFlushAfter, "空闲刷新时长");
        if (this.idleFlushAfter.compareTo(this.allowedLateness) <= 0) {
            throw new IllegalArgumentException("空闲刷新时长必须大于允许迟到时长");
        }
    }

    /**
     * 判断新到达事实是否已经落在当前水位容忍窗之外。
     *
     * @param watermark 当前标签水位
     * @param occurredAt     新事实发生时间
     * @param ingestSequence 新事实摄入序号
     * @return 严格早于水位边界时返回 {@code true}
     */
    public boolean isTooLate(
            Watermark watermark,
            Instant occurredAt,
            long ingestSequence
    ) {
        Objects.requireNonNull(watermark, "水位不能为空");
        Objects.requireNonNull(occurredAt, "发生时间不能为空");
        if (ingestSequence <= 0) {
            throw new IllegalArgumentException("摄入序号必须为正数");
        }
        boolean beforeLatenessBoundary = occurredAt.isBefore(
                watermark.maxOccurredAt().minus(allowedLateness));
        boolean beforeFinalizedFrontier = watermark.finalizedOccurredAt() != null
                && (occurredAt.isBefore(watermark.finalizedOccurredAt())
                || (occurredAt.equals(watermark.finalizedOccurredAt())
                && ingestSequence < watermark.finalizedIngestSequence()));
        return beforeLatenessBoundary || beforeFinalizedFrontier;
    }

    /**
     * 计算本次可以安全处理的最大事件时间。
     *
     * @param watermark 当前标签水位
     * @param now       平台可信当前时间
     * @return 正常流量下为迟到边界，空闲达到阈值后为最大事件时间
     */
    public Instant processingCutoff(Watermark watermark, Instant now) {
        Objects.requireNonNull(watermark, "水位不能为空");
        Objects.requireNonNull(now, "当前时间不能为空");
        if (!now.isBefore(watermark.lastArrivalAt().plus(idleFlushAfter))) {
            return watermark.maxOccurredAt();
        }
        return watermark.maxOccurredAt().minus(allowedLateness);
    }

    /**
     * 使用新到达事实单调推进水位。
     *
     * @param current        当前水位
     * @param occurredAt     新事实发生时间
     * @param ingestSequence 新事实摄入序号
     * @param arrivedAt      新事实平台到达时间
     * @return 推进后的不可变水位
     */
    public Watermark observe(
            Watermark current,
            Instant occurredAt,
            long ingestSequence,
            Instant arrivedAt
    ) {
        Objects.requireNonNull(current, "当前水位不能为空");
        Objects.requireNonNull(occurredAt, "发生时间不能为空");
        Objects.requireNonNull(arrivedAt, "到达时间不能为空");
        if (ingestSequence <= 0) {
            throw new IllegalArgumentException("摄入序号必须为正数");
        }
        return new Watermark(
                occurredAt.isAfter(current.maxOccurredAt())
                        ? occurredAt : current.maxOccurredAt(),
                Math.max(current.maxIngestSequence(), ingestSequence),
                arrivedAt.isAfter(current.lastArrivalAt())
                        ? arrivedAt : current.lastArrivalAt(),
                current.finalizedOccurredAt(),
                current.finalizedIngestSequence()
        );
    }

    /**
     * 返回允许迟到时长，供持久队列构造 SQL 水位边界。
     *
     * @return 允许迟到时长
     */
    public Duration allowedLateness() {
        return allowedLateness;
    }

    /**
     * 返回空闲刷新阈值，供持久队列构造 SQL 水位边界。
     *
     * @return 空闲刷新阈值
     */
    public Duration idleFlushAfter() {
        return idleFlushAfter;
    }

    /**
     * 单个矿区标签的事件时间水位快照。
     *
     * @param maxOccurredAt     已观察到的最大事件时间
     * @param maxIngestSequence 已观察到的最大摄入序号
     * @param lastArrivalAt              最近平台到达时间
     * @param finalizedOccurredAt        可选最终处理事件时间前沿
     * @param finalizedIngestSequence    可选最终处理摄入序号前沿
     */
    public record Watermark(
            Instant maxOccurredAt,
            long maxIngestSequence,
            Instant lastArrivalAt,
            Instant finalizedOccurredAt,
            Long finalizedIngestSequence
    ) {

        /**
         * 校验水位字段完整且摄入序号为正数。
         */
        public Watermark {
            Objects.requireNonNull(maxOccurredAt, "最大事件时间不能为空");
            Objects.requireNonNull(lastArrivalAt, "最近到达时间不能为空");
            if (maxIngestSequence <= 0) {
                throw new IllegalArgumentException("最大摄入序号必须为正数");
            }
            if ((finalizedOccurredAt == null) != (finalizedIngestSequence == null)) {
                throw new IllegalArgumentException("最终事件时间和摄入序号前沿必须同时存在");
            }
            if (finalizedIngestSequence != null && finalizedIngestSequence <= 0) {
                throw new IllegalArgumentException("最终摄入序号前沿必须为正数");
            }
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须为正数");
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + "不得为负数");
        }
        return value;
    }
}
