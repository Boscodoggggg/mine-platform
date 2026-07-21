package com.bosco.mine.telemetry.alarm;

import com.bosco.mine.model.strategy.AlarmStrategyDefinition;
import com.bosco.mine.telemetry.quality.QualityRuleCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Telemetry 热路径使用的、经过共享类型注册表再次编译的告警策略。
 *
 * @param snapshotId 不可变运行快照标识
 * @param runtimeRevision 单调运行修订号
 * @param contentHash 单设备有效策略摘要
 * @param rules 有序且类型安全的原子规则集合
 */
public record AlarmPolicy(
        UUID snapshotId,
        long runtimeRevision,
        String contentHash,
        List<Rule> rules
) {

    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** 创建并冻结类型安全的告警运行策略。 */
    public AlarmPolicy {
        snapshotId = Objects.requireNonNull(snapshotId, "告警策略快照标识不能为空");
        if (runtimeRevision < 1L) {
            throw new IllegalArgumentException("告警策略运行修订号必须大于 0");
        }
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("告警策略内容摘要必须是规范化 SHA-256");
        }
        rules = List.copyOf(Objects.requireNonNull(rules, "告警规则集合不能为空"));
        if (rules.isEmpty() || rules.size() > 100) {
            throw new IllegalArgumentException("告警规则数量必须在 1 到 100 之间");
        }
        if (rules.stream().map(Rule::key).distinct().count() != rules.size()) {
            throw new IllegalArgumentException("告警规则编码不能重复");
        }
    }

    /**
     * 使用共享告警类型定义重新校验数据库镜像并编译热路径对象。
     *
     * @param snapshotId 不可变运行快照标识
     * @param runtimeRevision 单调运行修订号
     * @param contentHash 单设备有效策略摘要
     * @param values 数据库中的告警策略 JSON 字段
     * @return 类型安全的告警运行策略
     */
    public static AlarmPolicy from(
            UUID snapshotId,
            long runtimeRevision,
            String contentHash,
            Map<String, ?> values
    ) {
        Map<String, Object> normalized = new AlarmStrategyDefinition().validate(values);
        List<?> rawRules = (List<?>) normalized.get(AlarmStrategyDefinition.RULES);
        List<Rule> compiled = new ArrayList<>(rawRules.size());
        for (Object rawRule : rawRules) {
            if (!(rawRule instanceof Map<?, ?> valuesByField)) {
                throw new IllegalArgumentException("规范化告警规则不是对象");
            }
            compiled.add(compileRule(valuesByField));
        }
        return new AlarmPolicy(snapshotId, runtimeRevision, contentHash, compiled);
    }

    private static Rule compileRule(Map<?, ?> values) {
        SourceType sourceType = SourceType.valueOf((String) values.get("sourceType"));
        return new Rule(
                (String) values.get("key"),
                (String) values.get("name"),
                sourceType,
                Severity.valueOf((String) values.get("severity")),
                (Integer) values.get("triggerDurationSeconds"),
                (Integer) values.get("recoverDurationSeconds"),
                (Integer) values.get("cooldownSeconds"),
                (Boolean) values.get("enabled"),
                (String) values.get("propertyId"),
                sourceType == SourceType.NUMERIC
                        ? Operator.valueOf((String) values.get("operator")) : null,
                (BigDecimal) values.get("threshold"),
                (BigDecimal) values.get("recoverThreshold"),
                sourceType == SourceType.QUALITY
                        ? QualityRuleCode.valueOf((String) values.get("qualityRuleCode")) : null
        );
    }

    /** 告警观测来源。 */
    public enum SourceType {
        /** 数值属性阈值。 */
        NUMERIC,
        /** 数据质量规则命中。 */
        QUALITY
    }

    /** 面向值班处置的告警等级。 */
    public enum Severity {
        /** 提示信息。 */
        INFO,
        /** 需要关注的警告。 */
        WARNING,
        /** 需要优先处置的严重告警。 */
        CRITICAL
    }

    /** 数值触发比较算子。 */
    public enum Operator {
        /** 大于。 */
        GT,
        /** 大于等于。 */
        GTE,
        /** 小于。 */
        LT,
        /** 小于等于。 */
        LTE
    }

    /**
     * 已编译的单条告警规则。
     *
     * @param key 稳定规则编码
     * @param name 规则显示名称
     * @param sourceType 观测来源
     * @param severity 告警等级
     * @param triggerDurationSeconds 触发持续秒数
     * @param recoverDurationSeconds 恢复持续秒数
     * @param cooldownSeconds 恢复后冷却秒数
     * @param enabled 是否启用
     * @param propertyId 目标属性，质量来源可为空
     * @param operator 数值比较算子，质量来源为空
     * @param threshold 数值触发阈值，质量来源为空
     * @param recoverThreshold 数值恢复阈值，质量来源为空
     * @param qualityRuleCode 数据质量规则编码，数值来源为空
     */
    public record Rule(
            String key,
            String name,
            SourceType sourceType,
            Severity severity,
            int triggerDurationSeconds,
            int recoverDurationSeconds,
            int cooldownSeconds,
            boolean enabled,
            String propertyId,
            Operator operator,
            BigDecimal threshold,
            BigDecimal recoverThreshold,
            QualityRuleCode qualityRuleCode
    ) {
        /** 校验已通过类型注册表编译的来源字段不变量。 */
        public Rule {
            key = Objects.requireNonNull(key, "告警规则编码不能为空");
            name = Objects.requireNonNull(name, "告警规则名称不能为空");
            sourceType = Objects.requireNonNull(sourceType, "告警来源不能为空");
            severity = Objects.requireNonNull(severity, "告警等级不能为空");
            if (key.isBlank() || name.isBlank()) {
                throw new IllegalArgumentException("告警规则编码和名称不能为空白");
            }
            if (triggerDurationSeconds < 0 || triggerDurationSeconds > 86_400
                    || recoverDurationSeconds < 0 || recoverDurationSeconds > 86_400
                    || cooldownSeconds < 0 || cooldownSeconds > 604_800) {
                throw new IllegalArgumentException("告警规则时间参数超出允许范围");
            }
            if (sourceType == SourceType.NUMERIC) {
                Objects.requireNonNull(propertyId, "数值告警属性不能为空");
                Objects.requireNonNull(operator, "数值告警算子不能为空");
                Objects.requireNonNull(threshold, "数值告警阈值不能为空");
                Objects.requireNonNull(recoverThreshold, "数值告警恢复阈值不能为空");
                if (qualityRuleCode != null) {
                    throw new IllegalArgumentException("数值告警不能包含质量规则编码");
                }
                boolean invalidHigh = (operator == Operator.GT || operator == Operator.GTE)
                        && recoverThreshold.compareTo(threshold) > 0;
                boolean invalidLow = (operator == Operator.LT || operator == Operator.LTE)
                        && recoverThreshold.compareTo(threshold) < 0;
                if (invalidHigh || invalidLow) {
                    throw new IllegalArgumentException("数值告警恢复阈值未形成合法迟滞区间");
                }
            } else {
                Objects.requireNonNull(qualityRuleCode, "质量告警规则编码不能为空");
                if (operator != null || threshold != null || recoverThreshold != null) {
                    throw new IllegalArgumentException("质量告警不能包含数值阈值字段");
                }
            }
        }
    }
}
