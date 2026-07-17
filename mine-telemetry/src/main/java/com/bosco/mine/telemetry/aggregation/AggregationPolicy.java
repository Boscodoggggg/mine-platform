package com.bosco.mine.telemetry.aggregation;

import com.bosco.mine.model.strategy.AggregationStrategyDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Telemetry 已独立校验的单设备数值聚合最后正确策略。
 *
 * @param snapshotId 不可变运行快照标识
 * @param runtimeRevision 产品内聚合策略运行修订
 * @param contentHash 单设备规范化策略摘要
 * @param enabled 是否允许执行聚合
 * @param correctionWindowSeconds 迟到数据修正窗口秒数
 * @param rules 固定属性与桶宽规则
 */
public record AggregationPolicy(
        UUID snapshotId,
        long runtimeRevision,
        String contentHash,
        boolean enabled,
        int correctionWindowSeconds,
        List<Rule> rules
) {

    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern PROPERTY_IDENTIFIER =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");

    /** 校验并冻结最后正确聚合策略。 */
    public AggregationPolicy {
        snapshotId = Objects.requireNonNull(snapshotId, "聚合策略快照标识不能为空");
        if (runtimeRevision < 1) {
            throw new IllegalArgumentException("聚合策略运行修订必须为正数");
        }
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("聚合策略内容摘要不合法");
        }
        if (correctionWindowSeconds < 300 || correctionWindowSeconds > 604_800) {
            throw new IllegalArgumentException("聚合策略修正窗口不合法");
        }
        rules = List.copyOf(Objects.requireNonNull(rules, "聚合规则不能为空"));
        if (rules.isEmpty() || rules.size() > 100) {
            throw new IllegalArgumentException("聚合规则数量不合法");
        }
    }

    /**
     * 使用共享白名单重新校验持久镜像并生成类型安全策略。
     *
     * @param snapshotId 不可变快照标识
     * @param runtimeRevision 运行修订
     * @param contentHash 单设备内容摘要
     * @param values 持久镜像中的最终有效字段
     * @return 不可变聚合策略
     */
    public static AggregationPolicy from(
            UUID snapshotId,
            long runtimeRevision,
            String contentHash,
            Map<String, ?> values
    ) {
        Map<String, Object> normalized =
                new AggregationStrategyDefinition().validate(values);
        Map<?, ?> policy = (Map<?, ?>) normalized.get(AggregationStrategyDefinition.POLICY);
        List<?> rawRules = (List<?>) policy.get(AggregationStrategyDefinition.RULES);
        List<Rule> rules = rawRules.stream()
                .map(candidate -> (Map<?, ?>) candidate)
                .map(rule -> new Rule(
                        (String) rule.get(AggregationStrategyDefinition.PROPERTY_ID),
                        (Integer) rule.get(AggregationStrategyDefinition.BUCKET_SECONDS)
                ))
                .toList();
        return new AggregationPolicy(
                snapshotId,
                runtimeRevision,
                contentHash,
                (Boolean) policy.get(AggregationStrategyDefinition.ENABLED),
                (Integer) policy.get(AggregationStrategyDefinition.CORRECTION_WINDOW_SECONDS),
                rules
        );
    }

    /**
     * 单个数值属性的固定桶聚合规则。
     *
     * @param propertyId 物模型数值属性标识
     * @param bucketSeconds 固定桶宽秒数
     */
    public record Rule(String propertyId, int bucketSeconds) {
        /** 校验运行规则不得缺失白名单字段。 */
        public Rule {
            propertyId = Objects.requireNonNull(propertyId, "聚合属性标识不能为空");
            if (!PROPERTY_IDENTIFIER.matcher(propertyId).matches()) {
                throw new IllegalArgumentException("聚合属性标识格式不合法");
            }
            if (!List.of(60, 300, 900, 3_600, 21_600, 86_400)
                    .contains(bucketSeconds)) {
                throw new IllegalArgumentException("聚合桶宽不合法");
            }
        }
    }
}
