package com.bosco.mine.telemetry.quality;

import com.bosco.mine.telemetry.pipeline.TelemetrySample;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 描述单帧数据质量策略实际执行到的规则和属性范围，避免把未执行误判为未命中。
 */
public final class QualityEvaluationCoverage {

    private static final QualityEvaluationCoverage NONE =
            new QualityEvaluationCoverage(Map.of(), Set.of());

    private final Map<QualityRuleCode, Set<String>> propertiesByRule;
    private final Set<QualityRuleCode> completeRules;

    private QualityEvaluationCoverage(
            Map<QualityRuleCode, Set<String>> propertiesByRule,
            Set<QualityRuleCode> completeRules
    ) {
        EnumMap<QualityRuleCode, Set<String>> copied = new EnumMap<>(QualityRuleCode.class);
        propertiesByRule.forEach((rule, properties) -> copied.put(
                Objects.requireNonNull(rule, "质量覆盖规则编码不能为空"),
                Set.copyOf(Objects.requireNonNull(properties, "质量覆盖属性不能为空"))));
        this.propertiesByRule = Map.copyOf(copied);
        this.completeRules = Set.copyOf(Objects.requireNonNull(
                completeRules, "质量完整覆盖规则不能为空"));
    }

    /**
     * 返回未执行任何显式质量规则的覆盖范围。
     *
     * @return 空覆盖范围
     */
    public static QualityEvaluationCoverage none() {
        return NONE;
    }

    /**
     * 根据策略配置和本帧实际数值属性计算可安全判定“未命中”的覆盖范围。
     *
     * @param policy 当前设备数据质量策略
     * @param samples 本帧实际生成的数值属性点
     * @return 不可变质量评估覆盖范围
     */
    public static QualityEvaluationCoverage from(
            DataQualityPolicy policy,
            List<TelemetrySample> samples
    ) {
        Objects.requireNonNull(policy, "数据质量策略不能为空");
        Set<String> presentProperties = new LinkedHashSet<>();
        for (TelemetrySample sample : Objects.requireNonNull(
                samples, "质量覆盖遥测点不能为空")) {
            presentProperties.add(Objects.requireNonNull(
                    sample, "质量覆盖遥测点不能为空").propertyId());
        }

        EnumMap<QualityRuleCode, Set<String>> properties =
                new EnumMap<>(QualityRuleCode.class);
        Set<QualityRuleCode> complete = new LinkedHashSet<>();
        addConfigured(
                properties, complete, QualityRuleCode.REQUIRED_MISSING,
                policy.requiredProperties(), policy.requiredProperties(), true);
        addConfigured(
                properties, complete, QualityRuleCode.BELOW_MINIMUM,
                policy.minimumValues().keySet(), presentProperties, false);
        addConfigured(
                properties, complete, QualityRuleCode.ABOVE_MAXIMUM,
                policy.maximumValues().keySet(), presentProperties, false);
        addTimeRule(
                properties, complete, QualityRuleCode.STALE_TIMESTAMP,
                policy.staleAfterSeconds(), presentProperties);
        addTimeRule(
                properties, complete, QualityRuleCode.FUTURE_TIMESTAMP,
                policy.futureToleranceSeconds(), presentProperties);
        return properties.isEmpty() ? NONE : new QualityEvaluationCoverage(properties, complete);
    }

    /**
     * 判断指定质量告警规则在本帧是否确实完成判定。
     *
     * @param ruleCode 数据质量规则编码
     * @param propertyId 目标属性；为空表示规则所有配置目标
     * @return 是否可以把无匹配证据解释为 CLEAR
     */
    public boolean covers(QualityRuleCode ruleCode, String propertyId) {
        Objects.requireNonNull(ruleCode, "质量覆盖规则编码不能为空");
        return propertyId == null
                ? completeRules.contains(ruleCode)
                : propertiesByRule.getOrDefault(ruleCode, Set.of()).contains(propertyId);
    }

    private static void addConfigured(
            Map<QualityRuleCode, Set<String>> properties,
            Set<QualityRuleCode> complete,
            QualityRuleCode ruleCode,
            Set<String> configured,
            Set<String> observed,
            boolean evaluateMissing
    ) {
        if (configured.isEmpty()) {
            return;
        }
        Set<String> covered = new LinkedHashSet<>(configured);
        if (!evaluateMissing) {
            covered.retainAll(observed);
        }
        if (!covered.isEmpty()) {
            properties.put(ruleCode, Set.copyOf(covered));
        }
        if (evaluateMissing || observed.containsAll(configured)) {
            complete.add(ruleCode);
        }
    }

    private static void addTimeRule(
            Map<QualityRuleCode, Set<String>> properties,
            Set<QualityRuleCode> complete,
            QualityRuleCode ruleCode,
            Integer threshold,
            Set<String> presentProperties
    ) {
        if (threshold == null || presentProperties.isEmpty()) {
            return;
        }
        properties.put(ruleCode, Set.copyOf(presentProperties));
        complete.add(ruleCode);
    }
}
