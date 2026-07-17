package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import com.bosco.mine.telemetry.quality.QualityEvaluationCoverage;
import com.bosco.mine.telemetry.quality.QualityEvidence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 对单帧遥测执行受控数值阈值或数据质量命中规则，不解释任意表达式。
 */
public final class AlarmRuleEvaluator {

    /**
     * 计算一条规则的单帧观测结果。
     *
     * @param rule 已编译告警规则
     * @param samples 本帧属性级遥测点
     * @param qualityEvidence 本帧结构化质量证据
     * @param qualityCoverage 本帧实际执行到的数据质量规则与属性范围
     * @return 触发、恢复、迟滞区或未知结果
     */
    public AlarmEvaluation evaluate(
            AlarmPolicy.Rule rule,
            List<TelemetrySample> samples,
            List<QualityEvidence> qualityEvidence,
            QualityEvaluationCoverage qualityCoverage
    ) {
        Objects.requireNonNull(rule, "告警规则不能为空");
        Objects.requireNonNull(samples, "告警判定遥测点不能为空");
        Objects.requireNonNull(qualityEvidence, "告警判定质量证据不能为空");
        Objects.requireNonNull(qualityCoverage, "数据质量评估覆盖范围不能为空");
        if (!rule.enabled()) {
            throw new IllegalArgumentException("禁用规则不能进入告警热路径");
        }
        return switch (rule.sourceType()) {
            case NUMERIC -> evaluateNumeric(rule, samples);
            case QUALITY -> evaluateQuality(
                    rule, qualityEvidence, qualityCoverage);
        };
    }

    private AlarmEvaluation evaluateNumeric(
            AlarmPolicy.Rule rule,
            List<TelemetrySample> samples
    ) {
        TelemetrySample sample = samples.stream()
                .filter(candidate -> rule.propertyId().equals(candidate.propertyId()))
                .findFirst()
                .orElse(null);
        if (sample == null || sample.quality() == Quality.BAD
                || sample.quality() == Quality.TIME_UNTRUSTED) {
            return new AlarmEvaluation(AlarmEvaluation.Outcome.UNKNOWN, null);
        }
        BigDecimal value = BigDecimal.valueOf(sample.value());
        boolean firing = switch (rule.operator()) {
            case GT -> value.compareTo(rule.threshold()) > 0;
            case GTE -> value.compareTo(rule.threshold()) >= 0;
            case LT -> value.compareTo(rule.threshold()) < 0;
            case LTE -> value.compareTo(rule.threshold()) <= 0;
        };
        boolean clear = switch (rule.operator()) {
            case GT -> value.compareTo(rule.recoverThreshold()) <= 0;
            case GTE -> value.compareTo(rule.recoverThreshold()) < 0;
            case LT -> value.compareTo(rule.recoverThreshold()) >= 0;
            case LTE -> value.compareTo(rule.recoverThreshold()) > 0;
        };
        AlarmEvaluation.Outcome outcome = firing
                ? AlarmEvaluation.Outcome.FIRING
                : clear ? AlarmEvaluation.Outcome.CLEAR : AlarmEvaluation.Outcome.BETWEEN;
        return new AlarmEvaluation(outcome, new AlarmEvidencePayload(
                value, rule.operator().name(), rule.threshold(),
                rule.recoverThreshold(), null));
    }

    private AlarmEvaluation evaluateQuality(
            AlarmPolicy.Rule rule,
            List<QualityEvidence> qualityEvidence,
            QualityEvaluationCoverage qualityCoverage
    ) {
        if (!qualityCoverage.covers(rule.qualityRuleCode(), rule.propertyId())) {
            return new AlarmEvaluation(AlarmEvaluation.Outcome.UNKNOWN, null);
        }
        boolean matched = qualityEvidence.stream().anyMatch(evidence ->
                evidence.ruleCode() == rule.qualityRuleCode()
                        && (rule.propertyId() == null
                        || rule.propertyId().equals(evidence.propertyId())));
        return new AlarmEvaluation(
                matched ? AlarmEvaluation.Outcome.FIRING : AlarmEvaluation.Outcome.CLEAR,
                new AlarmEvidencePayload(
                        null, null, null, null, rule.qualityRuleCode().name())
        );
    }
}
