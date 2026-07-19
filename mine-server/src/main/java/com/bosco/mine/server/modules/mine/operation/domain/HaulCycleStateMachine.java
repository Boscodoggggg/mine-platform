package com.bosco.mine.server.modules.mine.operation.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用不可变空间证据推进一次装载、运输和卸载循环的纯状态机。
 *
 * <p>该类型不访问数据库、时钟、Spring 或网络，调用方负责事务、锁、Inbox 和候选持久化。</p>
 */
public final class HaulCycleStateMachine {

    /**
     * 使用一条空间信号评估当前运输循环。
     *
     * @param state 当前不可变循环状态
     * @param signal 空间事件信号
     * @return 已推进、重复或需要人工处置的确定性结果
     */
    public Evaluation evaluate(CycleState state, SpatialSignal signal) {
        Objects.requireNonNull(state, "运输循环状态不能为空");
        Objects.requireNonNull(signal, "运输空间信号不能为空");

        if (state.containsEvent(signal.eventId())) {
            return Evaluation.duplicate(state);
        }
        if (!state.vehicleId().equals(signal.vehicleId())) {
            return Evaluation.candidate(state, CandidateReason.VEHICLE_MISMATCH);
        }
        boolean loadZone = state.loadZoneVersionId().equals(signal.zoneVersionId());
        boolean unloadZone = state.unloadZoneVersionId().equals(signal.zoneVersionId());
        if (!loadZone && !unloadZone) {
            return Evaluation.candidate(state, CandidateReason.ZONE_VERSION_MISMATCH);
        }
        if (state.isBeforeOrAtFinalizedFrontier(signal)) {
            return Evaluation.candidate(state, CandidateReason.OUT_OF_ORDER);
        }
        if (state.status() == CycleStatus.COMPLETED) {
            return Evaluation.candidate(state, CandidateReason.UNEXPECTED_EVENT);
        }

        ExpectedSignal expected = expected(state.status());
        boolean expectedZone = expected.loadZone() ? loadZone : unloadZone;
        if (!expectedZone || expected.eventType() != signal.eventType()) {
            return Evaluation.candidate(state, CandidateReason.UNEXPECTED_EVENT);
        }

        Evidence evidence = new Evidence(
                expected.evidenceRole(), signal.eventId(), signal.occurredAt(),
                signal.ingestSequence());
        EnumMap<EvidenceRole, Evidence> accepted = new EnumMap<>(EvidenceRole.class);
        accepted.putAll(state.evidence());
        accepted.put(expected.evidenceRole(), evidence);
        CycleState transitioned = new CycleState(
                expected.nextStatus(), state.cycleNumber(), state.vehicleId(),
                state.loadZoneVersionId(), state.unloadZoneVersionId(),
                signal.occurredAt(), signal.ingestSequence(), accepted);
        return Evaluation.transitioned(transitioned, expected.evidenceRole());
    }

    /**
     * 从已经完成的循环显式创建下一循环初态，并保留不可回退的事件顺序前沿。
     *
     * @param completed 已完成循环
     * @return 清空本趟证据后的下一循环
     */
    public CycleState nextCycle(CycleState completed) {
        Objects.requireNonNull(completed, "已完成运输循环不能为空");
        if (completed.status() != CycleStatus.COMPLETED) {
            throw new IllegalStateException("只有 COMPLETED 运输循环可以开始下一循环");
        }
        return new CycleState(
                CycleStatus.WAITING_LOAD, completed.cycleNumber() + 1,
                completed.vehicleId(), completed.loadZoneVersionId(),
                completed.unloadZoneVersionId(), completed.lastOccurredAt(),
                completed.lastIngestSequence(), Map.of());
    }

    private static ExpectedSignal expected(CycleStatus status) {
        return switch (status) {
            case WAITING_LOAD -> new ExpectedSignal(
                    true, EventType.ENTER, EvidenceRole.LOAD_ENTER, CycleStatus.LOADING);
            case LOADING -> new ExpectedSignal(
                    true, EventType.DWELL, EvidenceRole.LOAD_DWELL, CycleStatus.LOADED);
            case LOADED -> new ExpectedSignal(
                    true, EventType.EXIT, EvidenceRole.LOAD_EXIT, CycleStatus.HAULING);
            case HAULING -> new ExpectedSignal(
                    false, EventType.ENTER, EvidenceRole.UNLOAD_ENTER, CycleStatus.UNLOADING);
            case UNLOADING -> new ExpectedSignal(
                    false, EventType.DWELL, EvidenceRole.UNLOAD_DWELL, CycleStatus.COMPLETED);
            case COMPLETED -> throw new IllegalStateException("已完成循环不能继续接受空间证据");
        };
    }

    private record ExpectedSignal(
            boolean loadZone,
            EventType eventType,
            EvidenceRole evidenceRole,
            CycleStatus nextStatus
    ) {
    }

    /** 运输循环阶段。 */
    public enum CycleStatus {
        /** 等待进入装载区域。 */
        WAITING_LOAD,
        /** 已进入装载区域并等待停留确认。 */
        LOADING,
        /** 已完成装载停留并等待离开。 */
        LOADED,
        /** 正在由装载区驶向卸载区。 */
        HAULING,
        /** 已进入卸载区域并等待停留确认。 */
        UNLOADING,
        /** 本趟装卸证据完整。 */
        COMPLETED
    }

    /** 空间事件类型。 */
    public enum EventType {
        /** 进入区域。 */
        ENTER,
        /** 离开区域。 */
        EXIT,
        /** 区域内达到停留阈值。 */
        DWELL
    }

    /** 一趟运输需要的固定证据角色。 */
    public enum EvidenceRole {
        /** 进入装载区域。 */
        LOAD_ENTER,
        /** 装载区域停留完成。 */
        LOAD_DWELL,
        /** 离开装载区域。 */
        LOAD_EXIT,
        /** 进入卸载区域。 */
        UNLOAD_ENTER,
        /** 卸载区域停留完成。 */
        UNLOAD_DWELL
    }

    /** 状态机评估结果类型。 */
    public enum Outcome {
        /** 状态和证据已推进。 */
        TRANSITIONED,
        /** 相同空间事件已经成为本趟证据。 */
        DUPLICATE,
        /** 不能安全自动推进，需要保留为候选。 */
        CANDIDATE
    }

    /** 固定低基数候选原因。 */
    public enum CandidateReason {
        /** 空间事件车辆与分配车辆不一致。 */
        VEHICLE_MISMATCH,
        /** 空间事件区域版本不是冻结的装载或卸载版本。 */
        ZONE_VERSION_MISMATCH,
        /** 空间事件顺序早于已经确定的前沿。 */
        OUT_OF_ORDER,
        /** 区域或事件类型不符合当前步骤。 */
        UNEXPECTED_EVENT
    }

    /**
     * 不可变运输循环状态。
     *
     * @param status 当前循环阶段
     * @param cycleNumber 作业分配内从一开始递增的趟次序号
     * @param vehicleId 冻结的分配车辆标识
     * @param loadZoneVersionId 冻结的装载区域版本标识
     * @param unloadZoneVersionId 冻结的卸载区域版本标识
     * @param lastOccurredAt 最近接受证据的发生时间
     * @param lastIngestSequence 最近接受证据的摄入序号
     * @param evidence 本趟已经接受的固定角色证据
     */
    public record CycleState(
            CycleStatus status,
            long cycleNumber,
            UUID vehicleId,
            UUID loadZoneVersionId,
            UUID unloadZoneVersionId,
            Instant lastOccurredAt,
            long lastIngestSequence,
            Map<EvidenceRole, Evidence> evidence
    ) {

        /** 校验状态身份、顺序前沿和证据映射完整。 */
        public CycleState {
            Objects.requireNonNull(status, "运输循环阶段不能为空");
            Objects.requireNonNull(vehicleId, "运输循环车辆不能为空");
            Objects.requireNonNull(loadZoneVersionId, "装载区域版本不能为空");
            Objects.requireNonNull(unloadZoneVersionId, "卸载区域版本不能为空");
            if (loadZoneVersionId.equals(unloadZoneVersionId)) {
                throw new IllegalArgumentException("装载和卸载区域版本不能相同");
            }
            if (cycleNumber < 1) {
                throw new IllegalArgumentException("运输趟次序号必须为正数");
            }
            if ((lastOccurredAt == null) != (lastIngestSequence == 0L)
                    || lastIngestSequence < 0L) {
                throw new IllegalArgumentException("运输循环事件顺序前沿不完整");
            }
            evidence = Map.copyOf(Objects.requireNonNull(evidence, "运输循环证据不能为空"));
            evidence.forEach((role, value) -> {
                if (role == null || value == null || role != value.role()) {
                    throw new IllegalArgumentException("运输循环证据角色不一致");
                }
            });
        }

        /**
         * 创建第一趟等待装载的初始状态。
         *
         * @param vehicleId 分配车辆
         * @param loadZoneVersionId 装载区域版本
         * @param unloadZoneVersionId 卸载区域版本
         * @return 第一趟初始状态
         */
        public static CycleState initial(
                UUID vehicleId,
                UUID loadZoneVersionId,
                UUID unloadZoneVersionId
        ) {
            return new CycleState(
                    CycleStatus.WAITING_LOAD, 1L, vehicleId,
                    loadZoneVersionId, unloadZoneVersionId, null, 0L, Map.of());
        }

        private boolean containsEvent(UUID eventId) {
            return evidence.values().stream().anyMatch(item -> item.eventId().equals(eventId));
        }

        private boolean isBeforeOrAtFinalizedFrontier(SpatialSignal signal) {
            if (lastOccurredAt == null) {
                return false;
            }
            int timeOrder = signal.occurredAt().compareTo(lastOccurredAt);
            return timeOrder < 0
                    || timeOrder == 0 && signal.ingestSequence() <= lastIngestSequence;
        }
    }

    /**
     * 状态机输入的有界空间事件信号。
     *
     * @param eventId 空间事件标识
     * @param vehicleId 发生时车辆标识
     * @param zoneVersionId 不可变区域版本标识
     * @param eventType 进入、离开或停留类型
     * @param occurredAt 业务发生时间
     * @param ingestSequence 锚点位置事实摄入序号
     */
    public record SpatialSignal(
            UUID eventId,
            UUID vehicleId,
            UUID zoneVersionId,
            EventType eventType,
            Instant occurredAt,
            long ingestSequence
    ) {

        /** 校验空间事件信号身份和顺序字段。 */
        public SpatialSignal {
            Objects.requireNonNull(eventId, "空间事件标识不能为空");
            Objects.requireNonNull(vehicleId, "空间事件车辆不能为空");
            Objects.requireNonNull(zoneVersionId, "空间事件区域版本不能为空");
            Objects.requireNonNull(eventType, "空间事件类型不能为空");
            Objects.requireNonNull(occurredAt, "空间事件发生时间不能为空");
            if (ingestSequence < 1L) {
                throw new IllegalArgumentException("空间事件摄入序号必须为正数");
            }
        }
    }

    /**
     * 已被循环接受的空间事件证据。
     *
     * @param role 固定证据角色
     * @param eventId 空间事件标识
     * @param occurredAt 业务发生时间
     * @param ingestSequence 锚点位置事实摄入序号
     */
    public record Evidence(
            EvidenceRole role,
            UUID eventId,
            Instant occurredAt,
            long ingestSequence
    ) {

        /** 校验证据完整。 */
        public Evidence {
            Objects.requireNonNull(role, "运输证据角色不能为空");
            Objects.requireNonNull(eventId, "运输证据事件标识不能为空");
            Objects.requireNonNull(occurredAt, "运输证据发生时间不能为空");
            if (ingestSequence < 1L) {
                throw new IllegalArgumentException("运输证据摄入序号必须为正数");
            }
        }
    }

    /**
     * 状态机确定性评估结果。
     *
     * @param outcome 结果类型
     * @param state 返回的不可变状态
     * @param candidateReason 候选结果的固定原因
     * @param acceptedEvidence 已推进结果接受的证据角色
     */
    public record Evaluation(
            Outcome outcome,
            CycleState state,
            CandidateReason candidateReason,
            EvidenceRole acceptedEvidence
    ) {

        /** 校验三类结果只携带各自允许的字段。 */
        public Evaluation {
            Objects.requireNonNull(outcome, "运输评估结果不能为空");
            Objects.requireNonNull(state, "运输评估状态不能为空");
            boolean valid = switch (outcome) {
                case TRANSITIONED -> candidateReason == null && acceptedEvidence != null;
                case DUPLICATE -> candidateReason == null && acceptedEvidence == null;
                case CANDIDATE -> candidateReason != null && acceptedEvidence == null;
            };
            if (!valid) {
                throw new IllegalArgumentException("运输评估结果字段组合不合法");
            }
        }

        private static Evaluation transitioned(CycleState state, EvidenceRole evidence) {
            return new Evaluation(Outcome.TRANSITIONED, state, null, evidence);
        }

        private static Evaluation duplicate(CycleState state) {
            return new Evaluation(Outcome.DUPLICATE, state, null, null);
        }

        private static Evaluation candidate(CycleState state, CandidateReason reason) {
            return new Evaluation(Outcome.CANDIDATE, state, reason, null);
        }
    }
}
