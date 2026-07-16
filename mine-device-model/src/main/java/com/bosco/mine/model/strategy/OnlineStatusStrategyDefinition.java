package com.bosco.mine.model.strategy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 在线状态策略的首个生产白名单定义。
 *
 * <p>当前只开放离线判定阈值，避免尚未接入运行消费者的字段提前成为无效配置。</p>
 */
public final class OnlineStatusStrategyDefinition implements StrategyDefinition {

    /** 在线状态策略稳定编码。 */
    public static final String TYPE = "ONLINE_STATUS";
    /** 离线判定阈值字段。 */
    public static final String OFFLINE_THRESHOLD_SECONDS = "offlineThresholdSeconds";
    private static final int MAX_FIELDS = 32;
    private static final int MIN_THRESHOLD_SECONDS = 1;
    private static final int MAX_THRESHOLD_SECONDS = 604_800;

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
            throw invalid("$", "策略片段字段数量不能超过 32");
        }

        List<StrategyValidationException.Violation> violations = new ArrayList<>();
        Map<String, Object> normalized = new TreeMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.nullsFirst(String::compareTo)))
                .forEach(entry -> validateEntry(entry.getKey(), entry.getValue(), normalized, violations));
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
        if (!OFFLINE_THRESHOLD_SECONDS.equals(field)) {
            violations.add(new StrategyValidationException.Violation(
                    field == null ? "$" : field,
                    "在线状态策略不支持该字段"
            ));
            return;
        }
        Integer threshold = exactInteger(value);
        if (threshold == null) {
            violations.add(new StrategyValidationException.Violation(field, "离线阈值必须是整数秒"));
            return;
        }
        if (threshold < MIN_THRESHOLD_SECONDS || threshold > MAX_THRESHOLD_SECONDS) {
            violations.add(new StrategyValidationException.Violation(
                    field,
                    "离线阈值必须在 1 到 604800 秒之间"
            ));
            return;
        }
        normalized.put(field, threshold);
    }

    private Integer exactInteger(Object value) {
        try {
            if (value instanceof Byte number) return number.intValue();
            if (value instanceof Short number) return number.intValue();
            if (value instanceof Integer number) return number;
            if (value instanceof Long number) return Math.toIntExact(number);
            if (value instanceof BigInteger number) return number.intValueExact();
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
}
