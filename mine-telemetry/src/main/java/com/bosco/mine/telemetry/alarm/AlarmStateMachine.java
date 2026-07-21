package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 使用平台可信接收时间推进的纯持久告警状态机。
 */
public final class AlarmStateMachine {

    private final Supplier<UUID> occurrenceIdSupplier;

    /**
     * 创建告警状态机。
     *
     * @param occurrenceIdSupplier 仅在真正触发时生成 UUIDv7 周期标识
     */
    public AlarmStateMachine(Supplier<UUID> occurrenceIdSupplier) {
        this.occurrenceIdSupplier = Objects.requireNonNull(
                occurrenceIdSupplier, "告警周期标识生成器不能为空");
    }

    /**
     * 根据单帧观测推进状态，接收时间倒退时保持原状态。
     *
     * @param previous 已持久化状态
     * @param evaluation 单帧规则观测
     * @param receivedAt 平台可信接收时间
     * @param rule 已编译规则及时间参数
     * @return 新状态和至多一个可靠业务事件
     */
    public Transition advance(
            State previous,
            AlarmEvaluation evaluation,
            Instant receivedAt,
            AlarmPolicy.Rule rule
    ) {
        Objects.requireNonNull(previous, "告警前置状态不能为空");
        Objects.requireNonNull(evaluation, "告警观测不能为空");
        Objects.requireNonNull(receivedAt, "告警接收时间不能为空");
        Objects.requireNonNull(rule, "告警规则不能为空");
        if (!rule.enabled()) {
            throw new IllegalArgumentException("禁用规则不能推进告警状态机");
        }
        if (previous.lastReceivedAt() != null
                && receivedAt.isBefore(previous.lastReceivedAt())) {
            return new Transition(previous, null);
        }
        if (evaluation.outcome() == AlarmEvaluation.Outcome.UNKNOWN) {
            return new Transition(previous.withLastReceivedAt(receivedAt), null);
        }
        return switch (previous.status()) {
            case NORMAL -> advanceNormal(previous, evaluation, receivedAt, rule);
            case FIRING_PENDING -> advanceFiringPending(
                    previous, evaluation, receivedAt, rule);
            case ACTIVE -> advanceActive(previous, evaluation, receivedAt, rule);
            case RECOVERING_PENDING -> advanceRecovering(
                    previous, evaluation, receivedAt, rule);
            case COOLDOWN -> advanceCooldown(previous, evaluation, receivedAt, rule);
        };
    }

    private Transition advanceNormal(
            State previous,
            AlarmEvaluation evaluation,
            Instant receivedAt,
            AlarmPolicy.Rule rule
    ) {
        if (evaluation.outcome() != AlarmEvaluation.Outcome.FIRING) {
            return new Transition(State.normal(receivedAt), null);
        }
        if (rule.triggerDurationSeconds() == 0) {
            return raise(receivedAt, evaluation.evidence());
        }
        return new Transition(new State(
                Status.FIRING_PENDING, receivedAt, null, null,
                null, null, receivedAt), null);
    }

    private Transition advanceFiringPending(
            State previous,
            AlarmEvaluation evaluation,
            Instant receivedAt,
            AlarmPolicy.Rule rule
    ) {
        if (evaluation.outcome() != AlarmEvaluation.Outcome.FIRING) {
            return new Transition(State.normal(receivedAt), null);
        }
        if (elapsed(previous.candidateSince(), receivedAt, rule.triggerDurationSeconds())) {
            return raise(receivedAt, evaluation.evidence());
        }
        return new Transition(previous.withLastReceivedAt(receivedAt), null);
    }

    private Transition advanceActive(
            State previous,
            AlarmEvaluation evaluation,
            Instant receivedAt,
            AlarmPolicy.Rule rule
    ) {
        if (evaluation.outcome() != AlarmEvaluation.Outcome.CLEAR) {
            return new Transition(previous.withLastReceivedAt(receivedAt), null);
        }
        if (rule.recoverDurationSeconds() == 0) {
            return recover(previous, receivedAt, rule.cooldownSeconds(), evaluation.evidence());
        }
        return new Transition(new State(
                Status.RECOVERING_PENDING, receivedAt, previous.occurrenceId(),
                previous.raisedAt(), null, null, receivedAt), null);
    }

    private Transition advanceRecovering(
            State previous,
            AlarmEvaluation evaluation,
            Instant receivedAt,
            AlarmPolicy.Rule rule
    ) {
        if (evaluation.outcome() != AlarmEvaluation.Outcome.CLEAR) {
            State active = new State(
                    Status.ACTIVE, null, previous.occurrenceId(), previous.raisedAt(),
                    null, null, receivedAt);
            return new Transition(active, null);
        }
        if (elapsed(previous.candidateSince(), receivedAt, rule.recoverDurationSeconds())) {
            return recover(previous, receivedAt, rule.cooldownSeconds(), evaluation.evidence());
        }
        return new Transition(previous.withLastReceivedAt(receivedAt), null);
    }

    private Transition advanceCooldown(
            State previous,
            AlarmEvaluation evaluation,
            Instant receivedAt,
            AlarmPolicy.Rule rule
    ) {
        if (receivedAt.isBefore(previous.cooldownUntil())) {
            return new Transition(previous.withLastReceivedAt(receivedAt), null);
        }
        return advanceNormal(State.normal(receivedAt), evaluation, receivedAt, rule);
    }

    private Transition raise(Instant receivedAt, AlarmEvidencePayload evidence) {
        UUID occurrenceId = Objects.requireNonNull(
                occurrenceIdSupplier.get(), "告警周期标识不能为空");
        if (occurrenceId.version() != 7) {
            throw new IllegalArgumentException("告警周期标识必须是 UUIDv7");
        }
        State state = new State(
                Status.ACTIVE, null, occurrenceId, receivedAt,
                null, null, receivedAt);
        return new Transition(state, new Event(
                EventType.RAISED, occurrenceId, receivedAt, null, evidence));
    }

    private Transition recover(
            State previous,
            Instant receivedAt,
            int cooldownSeconds,
            AlarmEvidencePayload evidence
    ) {
        State state = new State(
                Status.COOLDOWN, null, previous.occurrenceId(), previous.raisedAt(),
                receivedAt, receivedAt.plusSeconds(cooldownSeconds), receivedAt);
        return new Transition(state, new Event(
                EventType.RECOVERED, previous.occurrenceId(), receivedAt,
                "CONDITION_CLEARED", evidence));
    }

    private boolean elapsed(Instant since, Instant at, int seconds) {
        return !Duration.between(since, at).minusSeconds(seconds).isNegative();
    }

    /** 告警状态机节点。 */
    public enum Status {
        NORMAL,
        FIRING_PENDING,
        ACTIVE,
        RECOVERING_PENDING,
        COOLDOWN
    }

    /** 状态迁移产生的可靠事件类型。 */
    public enum EventType {
        RAISED,
        RECOVERED
    }

    /**
     * 可直接映射到持久运行表的状态机时间字段。
     */
    public record State(
            Status status,
            Instant candidateSince,
            UUID occurrenceId,
            Instant raisedAt,
            Instant recoveredAt,
            Instant cooldownUntil,
            Instant lastReceivedAt
    ) {
        /** 校验每个状态节点的字段组合。 */
        public State {
            status = Objects.requireNonNull(status, "告警状态不能为空");
            boolean valid = switch (status) {
                case NORMAL -> candidateSince == null && occurrenceId == null
                        && raisedAt == null && recoveredAt == null && cooldownUntil == null;
                case FIRING_PENDING -> candidateSince != null && occurrenceId == null
                        && raisedAt == null && recoveredAt == null && cooldownUntil == null;
                case ACTIVE -> candidateSince == null && occurrenceId != null
                        && raisedAt != null && recoveredAt == null && cooldownUntil == null;
                case RECOVERING_PENDING -> candidateSince != null && occurrenceId != null
                        && raisedAt != null && recoveredAt == null && cooldownUntil == null;
                case COOLDOWN -> candidateSince == null && occurrenceId != null
                        && raisedAt != null && recoveredAt != null && cooldownUntil != null
                        && !recoveredAt.isBefore(raisedAt)
                        && !cooldownUntil.isBefore(recoveredAt);
            };
            if (!valid) {
                throw new IllegalArgumentException("告警状态字段组合不合法: " + status);
            }
        }

        /** 创建尚未接收遥测的初始正常状态。 */
        public static State normal() {
            return normal(null);
        }

        /** 创建已观察到指定平台时间的正常状态。 */
        public static State normal(Instant lastReceivedAt) {
            return new State(Status.NORMAL, null, null, null, null, null, lastReceivedAt);
        }

        /** 只推进最近可信接收时间，其他状态字段保持不变。 */
        public State withLastReceivedAt(Instant receivedAt) {
            return new State(
                    status, candidateSince, occurrenceId, raisedAt,
                    recoveredAt, cooldownUntil, receivedAt);
        }
    }

    /** 单次状态推进结果。 */
    public record Transition(State state, Event event) {
        /** 校验新状态不能为空。 */
        public Transition {
            state = Objects.requireNonNull(state, "告警迁移后状态不能为空");
        }
    }

    /**
     * 状态迁移产生的完整周期事件事实。
     */
    public record Event(
            EventType type,
            UUID occurrenceId,
            Instant occurredAt,
            String recoveryReason,
            AlarmEvidencePayload evidence
    ) {
        /** 校验触发和恢复事件的原因字段。 */
        public Event {
            type = Objects.requireNonNull(type, "告警迁移事件类型不能为空");
            occurrenceId = Objects.requireNonNull(occurrenceId, "告警周期标识不能为空");
            occurredAt = Objects.requireNonNull(occurredAt, "告警迁移事件时间不能为空");
            evidence = Objects.requireNonNull(evidence, "告警迁移事件证据不能为空");
            if ((type == EventType.RECOVERED) != (recoveryReason != null)) {
                throw new IllegalArgumentException("只有恢复事件必须携带恢复原因");
            }
        }
    }
}
