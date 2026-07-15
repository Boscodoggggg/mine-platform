package com.bosco.mine.telemetry.dedup;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 根据消息标识、设备启动周期、序列号和报文指纹判定遥测消息是否重复或乱序。
 *
 * <p>本类不访问数据库或缓存。调用方负责提供已经处理过的状态快照，因此同一套规则既可以
 * 用于单机内存实现，也可以用于后续的数据库或 Redis 实现。</p>
 */
public final class DeduplicationPolicy {

    private final Duration fingerprintWindow;

    /**
     * 创建去重策略。
     *
     * @param fingerprintWindow 无完整启动周期和序列号时的报文指纹去重窗口
     */
    public DeduplicationPolicy(Duration fingerprintWindow) {
        this.fingerprintWindow = requireNonNegative(fingerprintWindow, "报文指纹去重窗口");
    }

    /**
     * 生成当前消息的去重与顺序判定结果。
     *
     * @param candidate 待判定消息
     * @param state     已处理状态快照，必须按设备和消息类型隔离查询
     * @return 去重与顺序判定结果
     */
    public Decision decide(Candidate candidate, State state) {
        Objects.requireNonNull(candidate, "待判定消息不能为空");
        Objects.requireNonNull(state, "已处理状态不能为空");

        if (state.messageIdProcessed() || state.idempotencyKeyProcessed()) {
            return Decision.duplicate();
        }
        if (!candidate.hasReliableSequence()) {
            return decideWithoutReliableSequence(candidate, state);
        }
        if (state.candidateBootRetired()) {
            return new Decision(Outcome.STALE_BOOT, 0L);
        }
        if (state.currentBootId() == null) {
            return Decision.accepted(0L);
        }
        if (!candidate.bootId().equals(state.currentBootId())) {
            return new Decision(Outcome.DEVICE_RESTART, 0L);
        }
        if (state.highestSequence() == null) {
            return Decision.accepted(0L);
        }

        int comparison = Long.compare(candidate.sequence(), state.highestSequence());
        if (comparison == 0) {
            return Decision.duplicate();
        }
        if (comparison < 0) {
            return new Decision(Outcome.OUT_OF_ORDER, 0L);
        }
        return Decision.accepted(sequenceGap(state.highestSequence(), candidate.sequence()));
    }

    private Decision decideWithoutReliableSequence(Candidate candidate, State state) {
        Instant fingerprintSeenAt = state.matchingFingerprintSeenAt();
        if (fingerprintSeenAt != null && isWithinFingerprintWindow(
                fingerprintSeenAt,
                candidate.receivedAt()
        )) {
            return Decision.duplicate();
        }
        return new Decision(Outcome.LIMITED_DEDUPLICATION, 0L);
    }

    private boolean isWithinFingerprintWindow(Instant fingerprintSeenAt, Instant receivedAt) {
        Duration distance = Duration.between(fingerprintSeenAt, receivedAt).abs();
        return distance.compareTo(fingerprintWindow) <= 0;
    }

    private long sequenceGap(long highestSequence, long incomingSequence) {
        long difference = incomingSequence - highestSequence;
        return difference > 1L ? difference - 1L : 0L;
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name + "不能为空");
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
        return duration;
    }

    /**
     * 待判定消息中与幂等和顺序相关的最小信息。
     *
     * @param bootId              设备启动周期标识
     * @param sequence            启动周期内序列号
     * @param receivedAt          平台接收时间
     * @param fallbackFingerprint 无可靠序列号时使用的标准化报文指纹
     */
    public record Candidate(
            String bootId,
            Long sequence,
            Instant receivedAt,
            String fallbackFingerprint) {

        /**
         * 创建并校验待判定消息。
         */
        public Candidate {
            receivedAt = Objects.requireNonNull(receivedAt, "平台接收时间不能为空");
            fallbackFingerprint = requireText(fallbackFingerprint, "报文指纹");
            if (sequence != null && sequence < 0L) {
                throw new IllegalArgumentException("设备序列号不能为负数");
            }
        }

        /**
         * 判断消息是否具有可跨设备重启区分的可靠序列信息。
         *
         * @return 同时具有启动周期和序列号时返回 {@code true}
         */
        public boolean hasReliableSequence() {
            return bootId != null && !bootId.isBlank() && sequence != null;
        }
    }

    /**
     * 去重判定所需的已处理状态快照。
     *
     * @param messageIdProcessed        当前消息标识是否已经处理
     * @param idempotencyKeyProcessed   当前设备、启动周期、序列号和消息类型组成的精确幂等键是否已经处理
     * @param currentBootId             当前设备启动周期
     * @param highestSequence           当前启动周期已处理的最高序列号
     * @param candidateBootRetired      待判定消息所属启动周期是否已经结束
     * @param matchingFingerprintSeenAt 相同报文指纹最近一次处理时间
     */
    public record State(
            boolean messageIdProcessed,
            boolean idempotencyKeyProcessed,
            String currentBootId,
            Long highestSequence,
            boolean candidateBootRetired,
            Instant matchingFingerprintSeenAt) {

        /**
         * 创建并校验已处理状态快照。
         */
        public State {
            if (highestSequence != null && highestSequence < 0L) {
                throw new IllegalArgumentException("最高序列号不能为负数");
            }
        }
    }

    /**
     * 去重与消息顺序判定结果。
     *
     * @param outcome     判定类型
     * @param sequenceGap 当前消息之前缺失的连续序号数量
     */
    public record Decision(Outcome outcome, long sequenceGap) {

        /**
         * 创建并校验判定结果。
         */
        public Decision {
            outcome = Objects.requireNonNull(outcome, "去重判定类型不能为空");
            if (sequenceGap < 0L) {
                throw new IllegalArgumentException("序号缺口不能为负数");
            }
        }

        private static Decision accepted(long sequenceGap) {
            return new Decision(Outcome.ACCEPTED, sequenceGap);
        }

        private static Decision duplicate() {
            return new Decision(Outcome.DUPLICATE, 0L);
        }
    }

    /**
     * 消息的幂等与顺序判定类型。
     */
    public enum Outcome {
        /** 消息首次到达且顺序正常。 */
        ACCEPTED,
        /** 消息已经处理，不再产生历史写入和业务副作用。 */
        DUPLICATE,
        /** 同一启动周期中的较小序号延后到达。 */
        OUT_OF_ORDER,
        /** 启动周期变化，允许序列号重新开始。 */
        DEVICE_RESTART,
        /** 已结束启动周期的数据延迟到达，不得切换当前启动周期。 */
        STALE_BOOT,
        /** 缺少完整启动周期或序列号，只能执行有限去重。 */
        LIMITED_DEDUPLICATION
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空白字符串");
        }
        return value;
    }
}
