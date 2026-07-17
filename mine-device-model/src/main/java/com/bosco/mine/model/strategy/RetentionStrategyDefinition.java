package com.bosco.mine.model.strategy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 数据保留策略的安全白名单定义。
 *
 * <p>整个 {@code policy} 对象作为三级作用域中的原子字段覆盖。首版只允许安全启停、
 * 稳定保留等级、原始数值遥测天数和质量证据天数，不接受任意 SQL、表名或调度表达式。</p>
 */
public final class RetentionStrategyDefinition implements StrategyDefinition {

    /** 数据保留策略稳定编码。 */
    public static final String TYPE = "RETENTION";
    /** 原子保留策略字段。 */
    public static final String POLICY = "policy";
    /** 安全启停字段。 */
    public static final String ENABLED = "enabled";
    /** 保留等级字段。 */
    public static final String RETENTION_CLASS = "retentionClass";
    /** 原始数值遥测保留天数字段。 */
    public static final String RAW_TELEMETRY_DAYS = "rawTelemetryDays";
    /** 质量证据保留天数字段。 */
    public static final String QUALITY_EVIDENCE_DAYS = "qualityEvidenceDays";

    private static final int MINIMUM_DAYS = 7;
    private static final int MAXIMUM_DAYS = 3_650;
    private static final Set<String> POLICY_FIELDS = Set.of(
            ENABLED, RETENTION_CLASS, RAW_TELEMETRY_DAYS, QUALITY_EVIDENCE_DAYS
    );
    private static final Map<String, DayRange> CLASS_RANGES = Map.of(
            "SHORT", new DayRange(7, 30),
            "STANDARD", new DayRange(31, 180),
            "LONG", new DayRange(181, 730),
            "ARCHIVE", new DayRange(731, 3_650)
    );

    /** {@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> validate(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            throw invalid("$", "策略片段必须包含保留策略对象");
        }
        List<StrategyValidationException.Violation> violations = new ArrayList<>();
        values.keySet().stream()
                .sorted(java.util.Comparator.nullsFirst(String::compareTo))
                .filter(field -> !POLICY.equals(field))
                .forEach(field -> violations.add(new StrategyValidationException.Violation(
                        field == null ? "$" : field,
                        "数据保留策略不支持该顶层字段"
                )));

        Object policyValue = values.get(POLICY);
        if (!(policyValue instanceof Map<?, ?> policy)) {
            violations.add(new StrategyValidationException.Violation(
                    POLICY, "保留策略必须是对象"
            ));
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
            Map<?, ?> policy,
            List<StrategyValidationException.Violation> violations
    ) {
        Map<String, Object> normalized = new TreeMap<>();
        for (String field : POLICY_FIELDS.stream().sorted().toList()) {
            if (!policy.containsKey(field)) {
                violations.add(new StrategyValidationException.Violation(
                        POLICY + "." + field, "保留策略缺少必填字段"
                ));
            }
        }
        policy.keySet().stream()
                .map(key -> key instanceof String string ? string : null)
                .sorted(java.util.Comparator.nullsFirst(String::compareTo))
                .filter(field -> field == null || !POLICY_FIELDS.contains(field))
                .forEach(field -> violations.add(new StrategyValidationException.Violation(
                        POLICY + "." + (field == null ? "$" : field),
                        "保留策略不支持该内部字段"
                )));

        Object enabled = policy.get(ENABLED);
        if (policy.containsKey(ENABLED)) {
            if (enabled instanceof Boolean booleanValue) {
                normalized.put(ENABLED, booleanValue);
            } else {
                violations.add(new StrategyValidationException.Violation(
                        POLICY + "." + ENABLED, "启用状态必须是布尔值"
                ));
            }
        }

        String retentionClass = normalizeClass(policy.get(RETENTION_CLASS));
        if (policy.containsKey(RETENTION_CLASS)) {
            if (retentionClass == null || !CLASS_RANGES.containsKey(retentionClass)) {
                violations.add(new StrategyValidationException.Violation(
                        POLICY + "." + RETENTION_CLASS, "保留等级不在受控白名单中"
                ));
            } else {
                normalized.put(RETENTION_CLASS, retentionClass);
            }
        }

        Integer rawDays = validateDays(
                policy, RAW_TELEMETRY_DAYS, normalized, violations);
        Integer evidenceDays = validateDays(
                policy, QUALITY_EVIDENCE_DAYS, normalized, violations);
        if (retentionClass != null && CLASS_RANGES.containsKey(retentionClass)
                && rawDays != null && !CLASS_RANGES.get(retentionClass).contains(rawDays)) {
            normalized.remove(RAW_TELEMETRY_DAYS);
            violations.add(new StrategyValidationException.Violation(
                    POLICY + "." + RAW_TELEMETRY_DAYS,
                    "原始遥测天数与保留等级区间不一致"
            ));
        }
        if (rawDays != null && evidenceDays != null && evidenceDays < rawDays) {
            normalized.remove(QUALITY_EVIDENCE_DAYS);
            violations.add(new StrategyValidationException.Violation(
                    POLICY + "." + QUALITY_EVIDENCE_DAYS,
                    "质量证据保留天数不能短于原始遥测"
            ));
        }
        return normalized;
    }

    private Integer validateDays(
            Map<?, ?> policy,
            String field,
            Map<String, Object> normalized,
            List<StrategyValidationException.Violation> violations
    ) {
        if (!policy.containsKey(field)) {
            return null;
        }
        Integer days = exactInteger(policy.get(field));
        if (days == null || days < MINIMUM_DAYS || days > MAXIMUM_DAYS) {
            violations.add(new StrategyValidationException.Violation(
                    POLICY + "." + field,
                    "保留天数必须是 7 到 3650 之间的整数"
            ));
            return null;
        }
        normalized.put(field, days);
        return days;
    }

    private String normalizeClass(Object value) {
        if (!(value instanceof String string) || string.isBlank()) {
            return null;
        }
        return string.strip().toUpperCase(Locale.ROOT);
    }

    private Integer exactInteger(Object value) {
        try {
            if (value instanceof Byte number) return number.intValue();
            if (value instanceof Short number) return number.intValue();
            if (value instanceof Integer number) return number;
            if (value instanceof Long number) return Math.toIntExact(number);
            if (value instanceof BigInteger number) return number.intValueExact();
            if (value instanceof BigDecimal number) return number.intValueExact();
            if (value instanceof Float number && Float.isFinite(number)) {
                return BigDecimal.valueOf(number.doubleValue()).intValueExact();
            }
            if (value instanceof Double number && Double.isFinite(number)) {
                return BigDecimal.valueOf(number).intValueExact();
            }
            return null;
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private StrategyValidationException invalid(String field, String reason) {
        return new StrategyValidationException(List.of(
                new StrategyValidationException.Violation(field, reason)
        ));
    }

    /** 一个保留等级允许的原始遥测天数闭区间。 */
    private record DayRange(int minimum, int maximum) {
        /** 判断天数是否位于本等级区间。 */
        private boolean contains(int days) {
            return days >= minimum && days <= maximum;
        }
    }
}
