package com.bosco.mine.telemetry.quality;

import com.bosco.mine.telemetry.pipeline.TelemetrySample;

import java.util.List;
import java.util.Objects;

/**
 * 单帧遥测经过质量策略后的属性点和结构化证据。
 *
 * @param samples 保留原值并带属性级质量的数值遥测点
 * @param evidence 本帧命中的质量规则证据
 */
public record DataQualityEvaluation(
        List<TelemetrySample> samples,
        List<QualityEvidence> evidence
) {

    /** 冻结单帧质量判定结果。 */
    public DataQualityEvaluation {
        samples = List.copyOf(Objects.requireNonNull(samples, "质量判定遥测点不能为空"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "质量判定证据不能为空"));
    }
}
