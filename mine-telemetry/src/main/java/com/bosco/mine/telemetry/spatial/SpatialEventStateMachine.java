package com.bosco.mine.telemetry.spatial;

import com.bosco.mine.model.spatial.SpatialClassification;
import com.bosco.mine.model.spatial.SpatialClassifier;
import com.bosco.mine.model.spatial.SpatialPoint;
import com.bosco.mine.model.spatial.SpatialZone;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用位置事实事件时间推进进入、离开和单次停留事件的纯状态机。
 */
public final class SpatialEventStateMachine {

    private final SpatialClassifier classifier = new SpatialClassifier();

    /**
     * 使用已发布区域配置、上次状态和当前位置事实计算唯一下一状态。
     *
     * @param zone       已发布区域配置
     * @param priorState 上次状态，首次判定可为空
     * @param locationFact 当前位置事实
     * @return 不含数据库、时钟或网络副作用的状态转换结果
     */
    public TransitionResult evaluate(
            ZoneConfiguration zone,
            ZoneState priorState,
            LocationFact locationFact
    ) {
        Objects.requireNonNull(zone, "区域配置不能为空");
        Objects.requireNonNull(locationFact, "位置事实不能为空");
        ZoneState current = priorState == null
                || !priorState.zoneVersionId().equals(zone.zoneVersionId())
                ? ZoneState.initial(zone.zoneVersionId())
                : priorState;
        requireOrdered(current, locationFact);

        if (locationFact.quality() == LocationQuality.DEGRADED) {
            return skipped(current, locationFact, null, SkipReason.DEGRADED);
        }
        SpatialClassification classification = classifier.classify(
                zone.geometry(), locationFact.point());
        if (classification == SpatialClassification.BOUNDARY) {
            return skipped(current, locationFact, classification, SkipReason.BOUNDARY);
        }
        if (classification == SpatialClassification.HEIGHT_UNKNOWN) {
            return skipped(current, locationFact, classification, SkipReason.HEIGHT_UNKNOWN);
        }

        StableState desired = classification == SpatialClassification.INSIDE
                ? StableState.INSIDE : StableState.OUTSIDE;
        if (desired == current.stableState()) {
            return remain(zone, current, locationFact, classification);
        }
        return confirmTransition(zone, current, locationFact, classification, desired);
    }

    private TransitionResult remain(
            ZoneConfiguration zone,
            ZoneState current,
            LocationFact fact,
            SpatialClassification classification
    ) {
        Instant dwellEmittedAt = current.dwellEmittedAt();
        List<EmittedEvent> events = List.of();
        if (current.stableState() == StableState.INSIDE
                && zone.dwellSeconds() > 0
                && dwellEmittedAt == null
                && !fact.occurredAt().isBefore(
                        current.enteredAt().plusSeconds(zone.dwellSeconds()))) {
            dwellEmittedAt = fact.occurredAt();
            events = List.of(event(
                    zone, fact, EventType.DWELL,
                    StableState.INSIDE, StableState.INSIDE, 1));
        }
        ZoneState next = advance(
                current, fact, current.stableState(), null, 0,
                current.enteredAt(), dwellEmittedAt);
        return new TransitionResult(next, events, classification, SkipReason.NONE);
    }

    private TransitionResult confirmTransition(
            ZoneConfiguration zone,
            ZoneState current,
            LocationFact fact,
            SpatialClassification classification,
            StableState desired
    ) {
        int candidateCount = desired == current.candidateState()
                ? current.candidateCount() + 1 : 1;
        int threshold = desired == StableState.INSIDE
                ? zone.enterConfirmPoints() : zone.exitConfirmPoints();
        if (candidateCount < threshold) {
            ZoneState next = advance(
                    current, fact, current.stableState(), desired, candidateCount,
                    current.enteredAt(), current.dwellEmittedAt());
            return new TransitionResult(next, List.of(), classification, SkipReason.NONE);
        }

        StableState previous = current.stableState();
        Instant enteredAt = desired == StableState.INSIDE ? fact.occurredAt() : null;
        if (previous == StableState.UNKNOWN) {
            ZoneState baseline = advance(
                    current, fact, desired, null, 0, enteredAt, null);
            return new TransitionResult(
                    baseline, List.of(), classification, SkipReason.NONE);
        }
        EventType eventType = desired == StableState.INSIDE
                ? EventType.ENTER : EventType.EXIT;
        ZoneState next = advance(
                current, fact, desired, null, 0, enteredAt, null);
        return new TransitionResult(
                next,
                List.of(event(zone, fact, eventType, previous, desired, candidateCount)),
                classification,
                SkipReason.NONE);
    }

    private TransitionResult skipped(
            ZoneState current,
            LocationFact fact,
            SpatialClassification classification,
            SkipReason reason
    ) {
        ZoneState next = advance(
                current, fact, current.stableState(), null, 0,
                current.enteredAt(), current.dwellEmittedAt());
        return new TransitionResult(next, List.of(), classification, reason);
    }

    private ZoneState advance(
            ZoneState current,
            LocationFact fact,
            StableState stableState,
            StableState candidateState,
            int candidateCount,
            Instant enteredAt,
            Instant dwellEmittedAt
    ) {
        return new ZoneState(
                current.zoneVersionId(), stableState, candidateState, candidateCount,
                enteredAt, dwellEmittedAt, fact.locationEventId(), fact.occurredAt(),
                fact.ingestSequence());
    }

    private EmittedEvent event(
            ZoneConfiguration zone,
            LocationFact fact,
            EventType type,
            StableState previous,
            StableState current,
            int confirmationPoints
    ) {
        return new EmittedEvent(
                zone.zoneId(), zone.zoneVersionId(), fact.tagId(),
                fact.vehicleId(), fact.personId(), type, fact.locationEventId(),
                previous, current, confirmationPoints, fact.occurredAt());
    }

    private void requireOrdered(ZoneState state, LocationFact fact) {
        if (state.lastOccurredAt() == null) {
            return;
        }
        boolean before = fact.occurredAt().isBefore(state.lastOccurredAt());
        boolean sameTimeNotNewer = fact.occurredAt().equals(state.lastOccurredAt())
                && fact.ingestSequence() <= state.lastIngestSequence();
        if (before || sameTimeNotNewer) {
            throw new IllegalArgumentException("空间状态机只接受事件时间和摄入序号严格递增的事实");
        }
    }

    /**
     * 不可变发布区域及其消抖和停留参数。
     *
     * @param zoneId             稳定区域对象标识
     * @param zoneVersionId      不可变发布版本标识
     * @param geometry           统一坐标空间几何
     * @param enterConfirmPoints 进入连续确认点数
     * @param exitConfirmPoints  离开连续确认点数
     * @param dwellSeconds       停留阈值秒数，零表示关闭
     */
    public record ZoneConfiguration(
            UUID zoneId,
            UUID zoneVersionId,
            SpatialZone geometry,
            int enterConfirmPoints,
            int exitConfirmPoints,
            int dwellSeconds
    ) {

        /**
         * 校验区域身份和与控制面一致的参数上限。
         */
        public ZoneConfiguration {
            Objects.requireNonNull(zoneId, "稳定区域对象标识不能为空");
            Objects.requireNonNull(zoneVersionId, "区域发布版本标识不能为空");
            Objects.requireNonNull(geometry, "空间几何不能为空");
            if (enterConfirmPoints < 1 || enterConfirmPoints > 20
                    || exitConfirmPoints < 1 || exitConfirmPoints > 20) {
                throw new IllegalArgumentException("进入和离开确认点数必须在 1 到 20 之间");
            }
            if (dwellSeconds < 0 || dwellSeconds > 86_400) {
                throw new IllegalArgumentException("停留阈值必须在 0 到 86400 秒之间");
            }
        }
    }

    /**
     * 已按事件时间排序的位置事实。
     *
     * @param locationEventId 位置事实事件标识
     * @param tagId           定位标签标识
     * @param vehicleId       发生时可选车辆标识
     * @param personId        发生时可选人员标识
     * @param point           统一坐标位置点
     * @param quality         固定位置质量状态
     * @param occurredAt      事实发生时间
     * @param ingestSequence  平台摄入序号
     */
    public record LocationFact(
            UUID locationEventId,
            UUID tagId,
            UUID vehicleId,
            UUID personId,
            SpatialPoint point,
            LocationQuality quality,
            Instant occurredAt,
            long ingestSequence
    ) {

        /**
         * 校验位置事实完整且不会同时绑定车辆和人员。
         */
        public LocationFact {
            Objects.requireNonNull(locationEventId, "位置事实事件标识不能为空");
            Objects.requireNonNull(tagId, "定位标签标识不能为空");
            Objects.requireNonNull(point, "统一坐标位置点不能为空");
            Objects.requireNonNull(quality, "位置质量不能为空");
            Objects.requireNonNull(occurredAt, "事实发生时间不能为空");
            if (vehicleId != null && personId != null) {
                throw new IllegalArgumentException("位置事实不能同时绑定车辆和人员");
            }
            if (ingestSequence <= 0) {
                throw new IllegalArgumentException("摄入序号必须为正数");
            }
        }
    }

    /**
     * 可持久化恢复的单区域版本、单标签状态。
     *
     * @param zoneVersionId       区域发布版本标识
     * @param stableState         当前稳定状态
     * @param candidateState      待确认候选状态
     * @param candidateCount      候选连续点数量
     * @param enteredAt           本次进入事件时间
     * @param dwellEmittedAt      本次停留事件发出时间
     * @param lastLocationEventId 最近位置事实事件标识
     * @param lastOccurredAt      最近事实发生时间
     * @param lastIngestSequence  最近事实摄入序号
     */
    public record ZoneState(
            UUID zoneVersionId,
            StableState stableState,
            StableState candidateState,
            int candidateCount,
            Instant enteredAt,
            Instant dwellEmittedAt,
            UUID lastLocationEventId,
            Instant lastOccurredAt,
            long lastIngestSequence
    ) {

        /**
         * 校验候选计数、进入证据和最后位置顺序一致。
         */
        public ZoneState {
            Objects.requireNonNull(zoneVersionId, "区域发布版本标识不能为空");
            Objects.requireNonNull(stableState, "稳定状态不能为空");
            if ((candidateState == null) != (candidateCount == 0) || candidateCount < 0) {
                throw new IllegalArgumentException("候选状态和候选点数不一致");
            }
            if (stableState == StableState.INSIDE && enteredAt == null) {
                throw new IllegalArgumentException("区域内状态必须保留进入时间");
            }
            if (stableState != StableState.INSIDE
                    && (enteredAt != null || dwellEmittedAt != null)) {
                throw new IllegalArgumentException("未知或区域外状态不得保留进入或停留时间");
            }
            if (dwellEmittedAt != null
                    && (enteredAt == null || dwellEmittedAt.isBefore(enteredAt))) {
                throw new IllegalArgumentException("停留事件时间不得早于进入时间");
            }
            boolean noLast = lastLocationEventId == null
                    && lastOccurredAt == null && lastIngestSequence == 0;
            boolean completeLast = lastLocationEventId != null
                    && lastOccurredAt != null && lastIngestSequence > 0;
            if (!noLast && !completeLast) {
                throw new IllegalArgumentException("最后位置事实证据不完整");
            }
        }

        private static ZoneState initial(UUID zoneVersionId) {
            return new ZoneState(
                    zoneVersionId, StableState.UNKNOWN, null, 0,
                    null, null, null, null, 0);
        }
    }

    /**
     * 单次状态转换产生的不可变业务事件证据。
     *
     * @param zoneId               稳定区域对象标识
     * @param zoneVersionId        区域发布版本标识
     * @param tagId                定位标签标识
     * @param vehicleId            发生时可选车辆标识
     * @param personId             发生时可选人员标识
     * @param type                 事件类型
     * @param anchorLocationEventId 锚点位置事实事件标识
     * @param previousState        事件前稳定状态
     * @param currentState         事件后稳定状态
     * @param confirmationPoints   连续确认点数
     * @param occurredAt           事件发生时间
     */
    public record EmittedEvent(
            UUID zoneId,
            UUID zoneVersionId,
            UUID tagId,
            UUID vehicleId,
            UUID personId,
            EventType type,
            UUID anchorLocationEventId,
            StableState previousState,
            StableState currentState,
            int confirmationPoints,
            Instant occurredAt
    ) {
    }

    /**
     * 单个位置事实的纯状态转换结果。
     *
     * @param state          下一可恢复状态
     * @param events         本次新产生的零到一个空间事件
     * @param classification 几何分类，降级点未分类时为空
     * @param skipReason     固定低基数跳过原因
     */
    public record TransitionResult(
            ZoneState state,
            List<EmittedEvent> events,
            SpatialClassification classification,
            SkipReason skipReason
    ) {

        /**
         * 防止调用方修改事件集合或传入空状态。
         */
        public TransitionResult {
            Objects.requireNonNull(state, "下一状态不能为空");
            events = List.copyOf(Objects.requireNonNull(events, "事件列表不能为空"));
            Objects.requireNonNull(skipReason, "跳过原因不能为空");
            if (events.size() > 1) {
                throw new IllegalArgumentException("单个位置事实最多产生一个空间事件");
            }
        }
    }

    /** 固定位置质量状态。 */
    public enum LocationQuality {
        /** 可参与空间业务判定。 */
        GOOD,
        /** 只保留位置历史，不参与空间业务判定。 */
        DEGRADED
    }

    /** 区域稳定状态。 */
    public enum StableState {
        /** 尚未用连续优质位置点建立区域内外基线。 */
        UNKNOWN,
        /** 稳定区域外。 */
        OUTSIDE,
        /** 稳定区域内。 */
        INSIDE
    }

    /** 空间业务事件类型。 */
    public enum EventType {
        /** 进入区域。 */
        ENTER,
        /** 离开区域。 */
        EXIT,
        /** 持续停留达到阈值。 */
        DWELL
    }

    /** 固定低基数跳过原因。 */
    public enum SkipReason {
        /** 本次事实已正常参与判定。 */
        NONE,
        /** 位置质量降级。 */
        DEGRADED,
        /** 位置落在边界容差带。 */
        BOUNDARY,
        /** 带高度区域缺少位置高程。 */
        HEIGHT_UNKNOWN
    }
}
