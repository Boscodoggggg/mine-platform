package com.bosco.mine.model.strategy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 数据质量策略的生产白名单定义。
 *
 * <p>首版只允许时间可信度、必填属性和数值范围规则。策略只描述如何标记质量，
 * 不开放静默丢弃字段，避免错误配置制造不可追溯的数据缺口。</p>
 */
public final class DataQualityStrategyDefinition implements StrategyDefinition {

    /** 数据质量策略稳定编码。 */
    public static final String TYPE = "DATA_QUALITY";
    /** 陈旧时间阈值字段。 */
    public static final String STALE_AFTER_SECONDS = "staleAfterSeconds";
    /** 未来时间容忍字段。 */
    public static final String FUTURE_TOLERANCE_SECONDS = "futureToleranceSeconds";
    /** 必填属性字段前缀。 */
    public static final String REQUIRED_PREFIX = "required.";
    /** 属性最小值字段前缀。 */
    public static final String MINIMUM_PREFIX = "minimum.";
    /** 属性最大值字段前缀。 */
    public static final String MAXIMUM_PREFIX = "maximum.";

    private static final int MAX_FIELDS = 256;
    private static final int MAX_STALE_SECONDS = 604_800;
    private static final int MAX_FUTURE_TOLERANCE_SECONDS = 3_600;
    private static final int MAX_DECIMAL_SCALE = 12;
    private static final BigDecimal MAX_ABSOLUTE_VALUE = new BigDecimal("1000000000000000");
    private static final Pattern PROPERTY_IDENTIFIER =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,127}$");

    /** {@inheritDoc} */
    @Override
    public String type() {
        return TYPE;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> validate(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            throw invalid("$", "策略片段至少需要一个字段");
        }
        if (values.size() > MAX_FIELDS) {
            throw invalid("$", "策略片段字段数量不能超过 256");
        }

        List<StrategyValidationException.Violation> violations = new ArrayList<>();
        Map<String, Object> normalized = new TreeMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.nullsFirst(String::compareTo)))
                .forEach(entry -> validateEntry(
                        entry.getKey(), entry.getValue(), normalized, violations
                ));
        validateRanges(normalized, violations);
        if (!violations.isEmpty()) {
            throw new StrategyValidationException(violations);
        }
        return Map.copyOf(normalized);
    }

    private void validateEntry(
            String field,
            Object value,
            Map<String, Object> normalized,
            List<StrategyValidationException.Violation> violations
    ) {
        if (STALE_AFTER_SECONDS.equals(field)) {
            validateInteger(field, value, 1, MAX_STALE_SECONDS, normalized, violations);
            return;
        }
        if (FUTURE_TOLERANCE_SECONDS.equals(field)) {
            validateInteger(
                    field, value, 0, MAX_FUTURE_TOLERANCE_SECONDS, normalized, violations
            );
            return;
        }
        if (field != null && field.startsWith(REQUIRED_PREFIX)) {
            if (!validProperty(field.substring(REQUIRED_PREFIX.length()))) {
                violations.add(new StrategyValidationException.Violation(
                        field, "必填规则的属性标识不合法"
                ));
            } else if (!(value instanceof Boolean)) {
                violations.add(new StrategyValidationException.Violation(
                        field, "必填规则必须是布尔值"
                ));
            } else {
                normalized.put(field, value);
            }
            return;
        }
        if (field != null && (field.startsWith(MINIMUM_PREFIX)
                || field.startsWith(MAXIMUM_PREFIX))) {
            String prefix = field.startsWith(MINIMUM_PREFIX) ? MINIMUM_PREFIX : MAXIMUM_PREFIX;
            if (!validProperty(field.substring(prefix.length()))) {
                violations.add(new StrategyValidationException.Violation(
                        field, "范围规则的属性标识不合法"
                ));
                return;
            }
            BigDecimal decimal = decimal(value);
            if (decimal == null) {
                violations.add(new StrategyValidationException.Violation(
                        field, "属性范围必须是绝对值不超过 10^15 且小数位不超过 12 的有限数值"
                ));
            } else {
                normalized.put(field, decimal);
            }
            return;
        }
        violations.add(new StrategyValidationException.Violation(
                field == null ? "$" : field,
                "数据质量策略不支持该字段"
        ));
    }

    private void validateInteger(
            String field,
            Object value,
            int minimum,
            int maximum,
            Map<String, Object> normalized,
            List<StrategyValidationException.Violation> violations
    ) {
        Integer integer = exactInteger(value);
        if (integer == null || integer < minimum || integer > maximum) {
            violations.add(new StrategyValidationException.Violation(
                    field, "时间阈值必须是 " + minimum + " 到 " + maximum + " 之间的整数秒"
            ));
            return;
        }
        normalized.put(field, integer);
    }

    private void validateRanges(
            Map<String, Object> normalized,
            List<StrategyValidationException.Violation> violations
    ) {
        normalized.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(MINIMUM_PREFIX))
                .forEach(entry -> {
                    String propertyId = entry.getKey().substring(MINIMUM_PREFIX.length());
                    String maximumField = MAXIMUM_PREFIX + propertyId;
                    Object maximum = normalized.get(maximumField);
                    if (maximum instanceof BigDecimal maximumDecimal
                            && ((BigDecimal) entry.getValue()).compareTo(maximumDecimal) > 0) {
                        violations.add(new StrategyValidationException.Violation(
                                entry.getKey(), "属性最小值不能大于最大值"
                        ));
                        violations.add(new StrategyValidationException.Violation(
                                maximumField, "属性最大值不能小于最小值"
                        ));
                    }
                });
    }

    private boolean validProperty(String propertyId) {
        return PROPERTY_IDENTIFIER.matcher(propertyId).matches();
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

    private BigDecimal decimal(Object value) {
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

    private StrategyValidationException invalid(String field, String reason) {
        return new StrategyValidationException(List.of(
                new StrategyValidationException.Violation(field, reason)
        ));
    }
}
