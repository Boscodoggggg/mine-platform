package com.bosco.mine.telemetry.pipeline;

import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.telemetry.dedup.DeduplicationPolicy;
import com.bosco.mine.telemetry.time.LateDataPolicy;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 组合遥测消息的幂等、顺序和时间策略，生成不依赖存储实现的处理决策。
 */
public final class TelemetryPipeline {

    private final DeduplicationPolicy deduplicationPolicy;
    private final LateDataPolicy lateDataPolicy;

    /**
     * 创建遥测决策管线。
     *
     * @param deduplicationPolicy 去重和顺序策略
     * @param lateDataPolicy      迟到数据和设备时间策略
     */
    public TelemetryPipeline(
            DeduplicationPolicy deduplicationPolicy,
            LateDataPolicy lateDataPolicy) {
        this.deduplicationPolicy = Objects.requireNonNull(deduplicationPolicy, "去重策略不能为空");
        this.lateDataPolicy = Objects.requireNonNull(lateDataPolicy, "迟到数据策略不能为空");
    }

    /**
     * 为一帧遥测消息生成处理动作。
     *
     * @param frame   待处理遥测帧
     * @param context 已处理状态快照
     * @return 可直接供存储和实时计算阶段执行的决策
     */
    public Decision decide(Frame frame, Context context) {
        Objects.requireNonNull(frame, "遥测帧不能为空");
        Objects.requireNonNull(context, "遥测处理上下文不能为空");

        DeduplicationPolicy.Decision deduplicationDecision = deduplicationPolicy.decide(
                new DeduplicationPolicy.Candidate(
                        frame.bootId(),
                        frame.sequence(),
                        frame.receivedAt(),
                        frame.fallbackFingerprint()
                ),
                new DeduplicationPolicy.State(
                        context.messageIdProcessed(),
                        context.idempotencyKeyProcessed(),
                        context.currentBootId(),
                        context.highestSequence(),
                        context.candidateBootRetired(),
                        context.matchingFingerprintSeenAt()
                )
        );
        LateDataPolicy.Decision timeDecision = lateDataPolicy.decide(
                frame.occurredAt(),
                frame.receivedAt(),
                context.latestEffectiveAt(),
                frame.deviceTimeTrusted()
        );
        Set<Action> actions = actions(frame, context, deduplicationDecision, timeDecision);
        return new Decision(
                deduplicationDecision.outcome(),
                timeDecision.timeQuality(),
                timeDecision.effectiveAt(),
                timeDecision.late(),
                deduplicationDecision.sequenceGap(),
                actions
        );
    }

    private Set<Action> actions(
            Frame frame,
            Context context,
            DeduplicationPolicy.Decision deduplicationDecision,
            LateDataPolicy.Decision timeDecision) {
        if (deduplicationDecision.outcome() == DeduplicationPolicy.Outcome.DUPLICATE) {
            return Set.of();
        }
        EnumSet<Action> actions = EnumSet.of(Action.STORE_HISTORY);
        boolean sequenceAllowsLatest = deduplicationDecision.outcome()
                != DeduplicationPolicy.Outcome.OUT_OF_ORDER
                && deduplicationDecision.outcome() != DeduplicationPolicy.Outcome.STALE_BOOT;
        boolean timeAllowsLatest = timeDecision.updateLatest()
                || winsLatestTimeTie(frame, context, deduplicationDecision, timeDecision);
        if (sequenceAllowsLatest && timeAllowsLatest) {
            actions.add(Action.UPDATE_LATEST);
        }
        return Collections.unmodifiableSet(actions);
    }

    private boolean winsLatestTimeTie(
            Frame frame,
            Context context,
            DeduplicationPolicy.Decision deduplicationDecision,
            LateDataPolicy.Decision timeDecision) {
        if (context.latestEffectiveAt() == null
                || !timeDecision.effectiveAt().equals(context.latestEffectiveAt())) {
            return false;
        }
        if (deduplicationDecision.outcome() == DeduplicationPolicy.Outcome.DEVICE_RESTART) {
            return true;
        }
        return deduplicationDecision.outcome() == DeduplicationPolicy.Outcome.ACCEPTED
                && frame.bootId() != null
                && frame.sequence() != null
                && frame.bootId().equals(context.currentBootId())
                && context.highestSequence() != null
                && frame.sequence() > context.highestSequence();
    }

    /**
     * 遥测管线执行动作。
     */
    public enum Action {
        /** 保存历史遥测数据。 */
        STORE_HISTORY,
        /** 更新设备最新值。 */
        UPDATE_LATEST
    }

    /**
     * 遥测决策所需的标准帧元数据。
     *
     * @param messageId          平台统一消息标识
     * @param deviceId           设备标识
     * @param messageType        统一消息类型
     * @param bootId             设备启动周期标识
     * @param sequence           启动周期内序列号
     * @param occurredAt         设备声明的发生时间
     * @param receivedAt         平台接收时间
     * @param fallbackFingerprint 无可靠序列号时使用的标准化报文指纹
     * @param deviceTimeTrusted   上游质量校验是否确认设备时间可信
     */
    public record Frame(
            UUID messageId,
            String deviceId,
            MessageKind messageType,
            String bootId,
            Long sequence,
            Instant occurredAt,
            Instant receivedAt,
            String fallbackFingerprint,
            boolean deviceTimeTrusted) {

        /**
         * 创建并校验遥测帧元数据。
         */
        public Frame {
            messageId = Objects.requireNonNull(messageId, "消息标识不能为空");
            deviceId = requireText(deviceId, "设备标识");
            messageType = Objects.requireNonNull(messageType, "消息类型不能为空");
            occurredAt = Objects.requireNonNull(occurredAt, "设备发生时间不能为空");
            receivedAt = Objects.requireNonNull(receivedAt, "平台接收时间不能为空");
            fallbackFingerprint = requireText(fallbackFingerprint, "报文指纹");
        }
    }

    /**
     * 管线判定所需的已处理状态快照。
     *
     * @param messageIdProcessed        当前消息标识是否已经处理
     * @param idempotencyKeyProcessed   当前精确业务幂等键是否已经处理
     * @param currentBootId             当前设备启动周期
     * @param highestSequence           当前启动周期已处理的最高序列号
     * @param candidateBootRetired      待判定消息所属启动周期是否已经结束
     * @param matchingFingerprintSeenAt 相同报文指纹最近一次处理时间
     * @param latestEffectiveAt         当前设备已生效的最新排序时间
     */
    public record Context(
            boolean messageIdProcessed,
            boolean idempotencyKeyProcessed,
            String currentBootId,
            Long highestSequence,
            boolean candidateBootRetired,
            Instant matchingFingerprintSeenAt,
            Instant latestEffectiveAt) {
    }

    /**
     * 遥测管线的最终处理决策。
     *
     * @param deduplicationOutcome 去重与顺序判定类型
     * @param timeQuality          设备时间可信度
     * @param effectiveAt          参与最新值比较的有效时间
     * @param late                是否为迟到数据
     * @param sequenceGap         当前消息之前缺失的连续序号数量
     * @param actions             后续阶段允许执行的动作
     */
    public record Decision(
            DeduplicationPolicy.Outcome deduplicationOutcome,
            LateDataPolicy.TimeQuality timeQuality,
            Instant effectiveAt,
            boolean late,
            long sequenceGap,
            Set<Action> actions) {

        /**
         * 创建并校验遥测管线决策。
         */
        public Decision {
            deduplicationOutcome = Objects.requireNonNull(deduplicationOutcome, "去重判定不能为空");
            timeQuality = Objects.requireNonNull(timeQuality, "时间可信度不能为空");
            effectiveAt = Objects.requireNonNull(effectiveAt, "有效排序时间不能为空");
            if (sequenceGap < 0L) {
                throw new IllegalArgumentException("序号缺口不能为负数");
            }
            actions = immutableActions(actions);
        }

        /**
         * 判断后续阶段是否允许执行指定动作。
         *
         * @param action 待检查动作
         * @return 动作存在时返回 {@code true}
         */
        public boolean hasAction(Action action) {
            return actions.contains(Objects.requireNonNull(action, "处理动作不能为空"));
        }

        private static Set<Action> immutableActions(Set<Action> actions) {
            Objects.requireNonNull(actions, "处理动作集合不能为空");
            if (actions.isEmpty()) {
                return Set.of();
            }
            return Collections.unmodifiableSet(EnumSet.copyOf(actions));
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空白字符串");
        }
        return value;
    }
}
