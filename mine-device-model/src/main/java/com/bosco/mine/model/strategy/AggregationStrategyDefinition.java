package com.bosco.mine.model.strategy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 数值遥测聚合策略的生产白名单定义。
 *
 * <p>整个 {@code policy} 对象作为三级作用域中的原子字段覆盖。首版只允许固定桶宽、
 * 有界迟到修正窗口和显式属性标识，不接受 SQL、表达式、函数名或任意扩展字段。</p>
 */
public final class AggregationStrategyDefinition implements StrategyDefinition {

    /** 聚合策略稳定编码。 */
    public static final String TYPE = "AGGREGATION";
    /** 原子聚合策略字段。 */
    public static final String POLICY = "policy";
    /** 安全启停字段。 */
    public static final String ENABLED = "enabled";
    /** 迟到数据修正窗口秒数字段。 */
    public static final String CORRECTION_WINDOW_SECONDS = "correctionWindowSeconds";
    /** 数值属性聚合规则集合字段。 */
    public static final String RULES = "rules";
    /** 规则属性标识字段。 */
    public static final String PROPERTY_ID = "propertyId";
    /** 规则桶宽秒数字段。 */
    public static final String BUCKET_SECONDS = "bucketSeconds";

    private static final int MINIMUM_CORRECTION_SECONDS = 300;
    private static final int MAXIMUM_CORRECTION_SECONDS = 604_800;
    private static final int MAXIMUM_RULES = 100;
    private static final Pattern PROPERTY_IDENTIFIER =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");
    private static final Set<Integer> ALLOWED_BUCKET_SECONDS = Set.of(
            60, 300, 900, 3_600, 21_600, 86_400
    );
    private static final Set<String> POLICY_FIELDS = Set.of(
            ENABLED, CORRECTION_WINDOW_SECONDS, RULES
    );
    private static final Set<String> RULE_FIELDS = Set.of(PROPERTY_ID, BUCKET_SECONDS);

    /** {@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> validate(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            throw invalid("$", "策略片段必须包含聚合策略对象");
        }
        List<StrategyValidationException.Violation> violations = new ArrayList<>();
        values.keySet().stream()
                .sorted(Comparator.nullsFirst(String::compareTo))
                .filter(field -> !POLICY.equals(field))
                .forEach(field -> violations.add(violation(
                        field == null ? "$" : field,
                        "聚合策略不支持该顶层字段"
                )));

        Object policyValue = values.get(POLICY);
        if (!(policyValue instanceof Map<?, ?> policy)) {
            violations.add(violation(POLICY, "聚合策略必须是对象"));
            throw new StrategyValidationException(violations);
        }

        Map<String, Object> normalizedPolicy = validatePolicy(policy, violations);
        if (!violations.isEmpty()) {
            throw new StrategyValidationException(violations);
        }
        return Map.of(POLICY, Collections.unmodifiableMap(
                new LinkedHashMap<>(new TreeMap<>(normalizedPolicy))
        ));
    }

    private Map<String, Object> validatePolicy(
            Map<?, ?> source,
            List<StrategyValidationException.Violation> violations
    ) {
        Map<String, Object> policy = stringKeyed(source, POLICY, violations);
        POLICY_FIELDS.stream().sorted()
                .filter(field -> !policy.containsKey(field))
                .forEach(field -> violations.add(violation(
                        POLICY + "." + field, "聚合策略缺少必填字段"
                )));
        policy.keySet().stream().sorted()
                .filter(field -> !POLICY_FIELDS.contains(field))
                .forEach(field -> violations.add(violation(
                        POLICY + "." + field, "聚合策略不支持该内部字段"
                )));

        Map<String, Object> normalized = new TreeMap<>();
        Object enabled = policy.get(ENABLED);
        if (policy.containsKey(ENABLED)) {
            if (enabled instanceof Boolean booleanValue) {
                normalized.put(ENABLED, booleanValue);
            } else {
                violations.add(violation(
                        POLICY + "." + ENABLED, "启用状态必须是布尔值"
                ));
            }
        }

        Integer correctionWindow = exactInteger(policy.get(CORRECTION_WINDOW_SECONDS));
        if (policy.containsKey(CORRECTION_WINDOW_SECONDS)) {
            if (correctionWindow == null
                    || correctionWindow < MINIMUM_CORRECTION_SECONDS
                    || correctionWindow > MAXIMUM_CORRECTION_SECONDS) {
                violations.add(violation(
                        POLICY + "." + CORRECTION_WINDOW_SECONDS,
                        "迟到修正窗口必须是 300 到 604800 之间的整数秒"
                ));
                correctionWindow = null;
            } else {
                normalized.put(CORRECTION_WINDOW_SECONDS, correctionWindow);
            }
        }

        RuleValidation rules = validateRules(policy.get(RULES), violations);
        if (rules.normalized() != null) {
            normalized.put(RULES, rules.normalized());
        }
        if (correctionWindow != null && rules.maximumBucketSeconds() != null
                && correctionWindow < rules.maximumBucketSeconds() * 2) {
            normalized.remove(CORRECTION_WINDOW_SECONDS);
            violations.add(violation(
                    POLICY + "." + CORRECTION_WINDOW_SECONDS,
                    "迟到修正窗口不得小于最大聚合桶宽的两倍"
            ));
        }
        return normalized;
    }

    private RuleValidation validateRules(
            Object candidate,
            List<StrategyValidationException.Violation> violations
    ) {
        if (!(candidate instanceof List<?> rules)) {
            violations.add(violation(POLICY + "." + RULES, "聚合规则必须是数组"));
            return new RuleValidation(null, null);
        }
        if (rules.isEmpty() || rules.size() > MAXIMUM_RULES) {
            violations.add(violation(
                    POLICY + "." + RULES, "聚合规则数量必须是 1 到 100"
            ));
            return new RuleValidation(null, null);
        }

        List<Map<String, Object>> normalizedRules = new ArrayList<>(rules.size());
        Set<RuleKey> keys = new LinkedHashSet<>();
        Integer maximumBucket = null;
        for (int index = 0; index < rules.size(); index++) {
            Object ruleValue = rules.get(index);
            String path = rulePath(index);
            if (!(ruleValue instanceof Map<?, ?> ruleSource)) {
                violations.add(violation(path, "聚合规则必须是对象"));
                continue;
            }
            Map<String, Object> rule = stringKeyed(ruleSource, path, violations);
            Map<String, Object> normalizedRule = new TreeMap<>();
            String propertyId = validatePropertyId(index, rule, violations);
            Integer bucketSeconds = validateBucketSeconds(index, rule, violations);
            rule.keySet().stream().sorted()
                    .filter(field -> !RULE_FIELDS.contains(field))
                    .forEach(field -> violations.add(violation(
                            path + "." + field, "聚合规则不支持该字段"
                    )));
            if (propertyId != null) {
                normalizedRule.put(PROPERTY_ID, propertyId);
            }
            if (bucketSeconds != null) {
                normalizedRule.put(BUCKET_SECONDS, bucketSeconds);
                maximumBucket = maximumBucket == null
                        ? bucketSeconds : Math.max(maximumBucket, bucketSeconds);
            }
            if (propertyId != null && bucketSeconds != null
                    && !keys.add(new RuleKey(propertyId, bucketSeconds))) {
                violations.add(violation(path, "同一属性和桶宽的聚合规则不能重复"));
            }
            normalizedRules.add(Collections.unmodifiableMap(
                    new LinkedHashMap<>(normalizedRule)));
        }
        normalizedRules.sort(Comparator
                .comparing((Map<String, Object> rule) ->
                        String.valueOf(rule.get(PROPERTY_ID)))
                .thenComparingInt(rule -> (Integer) rule.getOrDefault(BUCKET_SECONDS, 0)));
        return new RuleValidation(List.copyOf(normalizedRules), maximumBucket);
    }

    private String validatePropertyId(
            int index,
            Map<String, Object> rule,
            List<StrategyValidationException.Violation> violations
    ) {
        Object value = rule.get(PROPERTY_ID);
        if (!(value instanceof String text)) {
            violations.add(violation(
                    rulePath(index) + "." + PROPERTY_ID, "属性标识必须是字符串"
            ));
            return null;
        }
        String normalized = text.strip();
        if (!PROPERTY_IDENTIFIER.matcher(normalized).matches()) {
            violations.add(violation(
                    rulePath(index) + "." + PROPERTY_ID, "属性标识格式不合法"
            ));
            return null;
        }
        return normalized;
    }

    private Integer validateBucketSeconds(
            int index,
            Map<String, Object> rule,
            List<StrategyValidationException.Violation> violations
    ) {
        Integer value = exactInteger(rule.get(BUCKET_SECONDS));
        if (value == null || !ALLOWED_BUCKET_SECONDS.contains(value)) {
            violations.add(violation(
                    rulePath(index) + "." + BUCKET_SECONDS,
                    "桶宽必须使用受支持的固定整数秒"
            ));
            return null;
        }
        return value;
    }

    private Map<String, Object> stringKeyed(
            Map<?, ?> source,
            String path,
            List<StrategyValidationException.Violation> violations
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String field) || field.isEmpty()) {
                violations.add(violation(path + ".$", "字段名必须是非空字符串"));
            } else {
                result.put(field, value);
            }
        });
        return result;
    }

    private Integer exactInteger(Object value) {
        try {
            if (value instanceof Byte number) return number.intValue();
            if (value instanceof Short number) return number.intValue();
            if (value instanceof Integer number) return number;
            if (value instanceof Long number) return Math.toIntExact(number);
            if (value instanceof BigInteger number) return number.intValueExact();
            if (value instanceof BigDecimal number && number.scale() == 0) {
                return number.intValueExact();
            }
            return null;
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private String rulePath(int index) {
        return POLICY + "." + RULES + "[" + index + "]";
    }

    private StrategyValidationException.Violation violation(String field, String reason) {
        return new StrategyValidationException.Violation(field, reason);
    }

    private StrategyValidationException invalid(String field, String reason) {
        return new StrategyValidationException(List.of(violation(field, reason)));
    }

    /** 已规范化的规则集合和最大桶宽。 */
    private record RuleValidation(
            List<Map<String, Object>> normalized,
            Integer maximumBucketSeconds
    ) {
    }

    /** 用于拒绝同一属性和桶宽重复声明的稳定键。 */
    private record RuleKey(String propertyId, int bucketSeconds) {
    }
}
