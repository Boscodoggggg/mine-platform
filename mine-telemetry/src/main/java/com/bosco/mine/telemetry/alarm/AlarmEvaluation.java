package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;

import java.util.Objects;

/**
 * 单条告警规则对一帧遥测的纯函数观测结果。
 *
 * @param outcome 触发、恢复、迟滞区或未知结果
 * @param evidence 有观测时的结构化证据，未知结果为空
 */
public record AlarmEvaluation(
        Outcome outcome,
        AlarmEvidencePayload evidence
) {

    /** 校验未知与有证据结果之间的字段不变量。 */
    public AlarmEvaluation {
        outcome = Objects.requireNonNull(outcome, "告警观测结果不能为空");
        if ((outcome == Outcome.UNKNOWN) != (evidence == null)) {
            throw new IllegalArgumentException("未知告警观测不能带证据，其他结果必须带证据");
        }
    }

    /** 单帧相对于触发阈值和恢复阈值的观测结果。 */
    public enum Outcome {
        /** 触发条件成立。 */
        FIRING,
        /** 恢复条件成立。 */
        CLEAR,
        /** 位于触发与恢复阈值之间的迟滞区。 */
        BETWEEN,
        /** 缺值或质量不可用，不能推进或回退候选。 */
        UNKNOWN
    }
}
