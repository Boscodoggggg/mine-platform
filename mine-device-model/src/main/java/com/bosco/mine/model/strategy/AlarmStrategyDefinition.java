package com.bosco.mine.model.strategy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 双来源告警策略的生产白名单定义。
 *
 * <p>规则集合是三级作用域中的原子字段。首版只允许数值阈值和数据质量命中，
 * 不接受任意脚本、表达式或不受控扩展字段。</p>
 */
public final class AlarmStrategyDefinition implements StrategyDefinition {

    /** 告警策略稳定编码。 */
    public static final String TYPE = "ALARM";
    /** 原子规则集合字段。 */
    public static final String RULES = "rules";

    private static final int MAX_RULES = 100;
    private static final int MAX_DURATION_SECONDS = 86_400;
    private static final int MAX_COOLDOWN_SECONDS = 604_800;
    private static final int MAX_DECIMAL_SCALE = 12;
    private static final BigDecimal MAX_ABSOLUTE_VALUE =
            new BigDecimal("1000000000000000");
    private static final Pattern RULE_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern PROPERTY_IDENTIFIER =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");
    private static final Set<String> COMMON_FIELDS = Set.of(
            "key", "name", "sourceType", "severity", "triggerDurationSeconds",
            "recoverDurationSeconds", "cooldownSeconds", "enabled"
    );
    private static final Set<String> NUMERIC_FIELDS = Set.of(
            "propertyId", "operator", "threshold", "recoverThreshold"
    );
    private static final Set<String> QUALITY_FIELDS = Set.of(
            "propertyId", "qualityRuleCode"
    );
    private static final Set<String> SOURCE_TYPES = Set.of("NUMERIC", "QUALITY");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");
    private static final Set<String> OPERATORS = Set.of("GT", "GTE", "LT", "LTE");
    private static final Set<String> QUALITY_RULE_CODES = Set.of(
            "FUTURE_TIMESTAMP", "STALE_TIMESTAMP", "REQUIRED_MISSING",
            "BELOW_MINIMUM", "ABOVE_MAXIMUM"
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
            throw invalid("$", "告警策略必须包含规则集合");
        }
        List<StrategyValidationException.Violation> violations = new ArrayList<>();
        values.keySet().stream()
                .sorted(Comparator.nullsFirst(String::compareTo))
                .filter(field -> !RULES.equals(field))
                .forEach(field -> violations.add(violation(
                        field == null ? "$" : field, "告警策略不支持该顶层字段")));

        Object candidate = values.get(RULES);
        if (!(candidate instanceof List<?> rules)) {
            violations.add(violation(RULES, "告警规则必须是数组"));
            throw new StrategyValidationException(violations);
        }
        if (rules.isEmpty() || rules.size() > MAX_RULES) {
            violations.add(violation(RULES, "告警规则数量必须是 1 到 100"));
            throwIfInvalid(violations);
        }

        Set<String> ruleKeys = new LinkedHashSet<>();
        List<Map<String, Object>> normalizedRules = new ArrayList<>(rules.size());
        for (int index = 0; index < rules.size(); index++) {
            Object rule = rules.get(index);
            if (!(rule instanceof Map<?, ?> ruleMap)) {
                violations.add(violation(path(index), "告警规则必须是对象"));
                continue;
            }
            normalizedRules.add(validateRule(index, ruleMap, ruleKeys, violations));
        }
        throwIfInvalid(violations);
        return Map.of(RULES, Collections.unmodifiableList(normalizedRules));
    }

    private Map<String, Object> validateRule(
            int index,
            Map<?, ?> source,
            Set<String> ruleKeys,
            List<StrategyValidationException.Violation> violations
    ) {
        Map<String, Object> rule = stringKeyed(index, source, violations);
        Map<String, Object> normalized = new LinkedHashMap<>();
        String key = text(index, rule, "key", 64, violations);
        if (key != null) {
            if (!RULE_KEY.matcher(key).matches()) {
                violations.add(violation(path(index, "key"), "规则编码格式不合法"));
            } else if (!ruleKeys.add(key)) {
                violations.add(violation(path(index, "key"), "规则编码不能重复"));
            }
            normalized.put("key", key);
        }
        putText(normalized, "name", text(index, rule, "name", 100, violations));
        String sourceType = enumeration(
                index, rule, "sourceType", SOURCE_TYPES, violations);
        putText(normalized, "sourceType", sourceType);
        putText(normalized, "severity", enumeration(
                index, rule, "severity", SEVERITIES, violations));
        putInteger(normalized, "triggerDurationSeconds", integer(
                index, rule, "triggerDurationSeconds", 0, MAX_DURATION_SECONDS, violations));
        putInteger(normalized, "recoverDurationSeconds", integer(
                index, rule, "recoverDurationSeconds", 0, MAX_DURATION_SECONDS, violations));
        putInteger(normalized, "cooldownSeconds", integer(
                index, rule, "cooldownSeconds", 0, MAX_COOLDOWN_SECONDS, violations));
        normalized.put("enabled", booleanValue(index, rule, "enabled", true, violations));

        if ("NUMERIC".equals(sourceType)) {
            validateNumeric(index, rule, normalized, violations);
        } else if ("QUALITY".equals(sourceType)) {
            validateQuality(index, rule, normalized, violations);
        }
        rejectUnknownFields(index, rule, sourceType, violations);
        return Collections.unmodifiableMap(normalized);
    }

    private void validateNumeric(
            int index,
            Map<String, Object> rule,
            Map<String, Object> normalized,
            List<StrategyValidationException.Violation> violations
    ) {
        putText(normalized, "propertyId", property(index, rule, false, violations));
        String operator = enumeration(index, rule, "operator", OPERATORS, violations);
        putText(normalized, "operator", operator);
        BigDecimal threshold = decimal(index, rule, "threshold", violations);
        BigDecimal recoverThreshold = decimal(index, rule, "recoverThreshold", violations);
        if (threshold != null) {
            normalized.put("threshold", threshold);
        }
        if (recoverThreshold != null) {
            normalized.put("recoverThreshold", recoverThreshold);
        }
        if (operator != null && threshold != null && recoverThreshold != null) {
            boolean invalidHigh = ("GT".equals(operator) || "GTE".equals(operator))
                    && recoverThreshold.compareTo(threshold) > 0;
            boolean invalidLow = ("LT".equals(operator) || "LTE".equals(operator))
                    && recoverThreshold.compareTo(threshold) < 0;
            if (invalidHigh || invalidLow) {
                violations.add(violation(
                        path(index, "recoverThreshold"), "恢复阈值未形成合法迟滞区间"));
            }
        }
    }

    private void validateQuality(
            int index,
            Map<String, Object> rule,
            Map<String, Object> normalized,
            List<StrategyValidationException.Violation> violations
    ) {
        putText(normalized, "qualityRuleCode", enumeration(
                index, rule, "qualityRuleCode", QUALITY_RULE_CODES, violations));
        String propertyId = property(index, rule, true, violations);
        if (propertyId != null) {
            normalized.put("propertyId", propertyId);
        }
    }

    private Map<String, Object> stringKeyed(
            int index,
            Map<?, ?> source,
            List<StrategyValidationException.Violation> violations
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String field) || field.isEmpty()) {
                violations.add(violation(path(index), "规则字段名必须是非空字符串"));
            } else {
                result.put(field, value);
            }
        });
        return result;
    }

    private void rejectUnknownFields(
            int index,
            Map<String, Object> rule,
            String sourceType,
            List<StrategyValidationException.Violation> violations
    ) {
        Set<String> allowed = new LinkedHashSet<>(COMMON_FIELDS);
        if ("NUMERIC".equals(sourceType)) {
            allowed.addAll(NUMERIC_FIELDS);
        } else if ("QUALITY".equals(sourceType)) {
            allowed.addAll(QUALITY_FIELDS);
        } else {
            allowed.addAll(NUMERIC_FIELDS);
            allowed.addAll(QUALITY_FIELDS);
        }
        rule.keySet().stream().sorted()
                .filter(field -> !allowed.contains(field))
                .forEach(field -> violations.add(violation(
                        path(index, field), "告警来源不支持该字段")));
    }

    private String text(
            int index,
            Map<String, Object> rule,
            String field,
            int maximumLength,
            List<StrategyValidationException.Violation> violations
    ) {
        Object value = rule.get(field);
        if (!(value instanceof String text)) {
            violations.add(violation(path(index, field), "字段必须是字符串"));
            return null;
        }
        String normalized = text.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            violations.add(violation(path(index, field), "字段长度不合法"));
            return null;
        }
        return normalized;
    }

    private String enumeration(
            int index,
            Map<String, Object> rule,
            String field,
            Set<String> allowed,
            List<StrategyValidationException.Violation> violations
    ) {
        Object value = rule.get(field);
        if (!(value instanceof String text)) {
            violations.add(violation(path(index, field), "字段必须使用受支持的枚举值"));
            return null;
        }
        String normalized = text.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            violations.add(violation(path(index, field), "字段必须使用受支持的枚举值"));
            return null;
        }
        return normalized;
    }

    private String property(
            int index,
            Map<String, Object> rule,
            boolean optional,
            List<StrategyValidationException.Violation> violations
    ) {
        if (optional && !rule.containsKey("propertyId")) {
            return null;
        }
        String propertyId = text(index, rule, "propertyId", 128, violations);
        if (propertyId != null && !PROPERTY_IDENTIFIER.matcher(propertyId).matches()) {
            violations.add(violation(path(index, "propertyId"), "物模型属性标识不合法"));
        }
        return propertyId;
    }

    private Integer integer(
            int index,
            Map<String, Object> rule,
            String field,
            int minimum,
            int maximum,
            List<StrategyValidationException.Violation> violations
    ) {
        Integer value = exactInteger(rule.get(field));
        if (value == null || value < minimum || value > maximum) {
            violations.add(violation(path(index, field), "秒数超出允许范围"));
            return null;
        }
        return value;
    }

    private boolean booleanValue(
            int index,
            Map<String, Object> rule,
            String field,
            boolean defaultValue,
            List<StrategyValidationException.Violation> violations
    ) {
        if (!rule.containsKey(field)) {
            return defaultValue;
        }
        Object value = rule.get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        violations.add(violation(path(index, field), "字段必须是布尔值"));
        return defaultValue;
    }

    private BigDecimal decimal(
            int index,
            Map<String, Object> rule,
            String field,
            List<StrategyValidationException.Violation> violations
    ) {
        BigDecimal value = normalizedDecimal(rule.get(field));
        if (value == null) {
            violations.add(violation(path(index, field), "阈值必须是范围内的有限数值"));
        }
        return value;
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

    private BigDecimal normalizedDecimal(Object value) {
        try {
            BigDecimal decimal;
            if (value instanceof BigDecimal number) {
                decimal = number;
            } else if (value instanceof BigInteger number) {
                decimal = new BigDecimal(number);
            } else if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                decimal = BigDecimal.valueOf(((Number) value).longValue());
            } else if (value instanceof Float number && Float.isFinite(number)) {
                decimal = BigDecimal.valueOf(number.doubleValue());
            } else if (value instanceof Double number && Double.isFinite(number)) {
                decimal = BigDecimal.valueOf(number);
            } else {
                return null;
            }
            BigDecimal normalized = decimal.signum() == 0
                    ? BigDecimal.ZERO
                    : decimal.stripTrailingZeros();
            if (normalized.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0
                    || Math.max(normalized.scale(), 0) > MAX_DECIMAL_SCALE) {
                return null;
            }
            return normalized;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void putText(Map<String, Object> target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private void putInteger(Map<String, Object> target, String field, Integer value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    private String path(int index) {
        return RULES + "[" + index + "]";
    }

    private String path(int index, String field) {
        return path(index) + "." + field;
    }

    private StrategyValidationException.Violation violation(String field, String reason) {
        return new StrategyValidationException.Violation(field, reason);
    }

    private StrategyValidationException invalid(String field, String reason) {
        return new StrategyValidationException(List.of(violation(field, reason)));
    }

    private void throwIfInvalid(List<StrategyValidationException.Violation> violations) {
        if (!violations.isEmpty()) {
            throw new StrategyValidationException(violations);
        }
    }
}
