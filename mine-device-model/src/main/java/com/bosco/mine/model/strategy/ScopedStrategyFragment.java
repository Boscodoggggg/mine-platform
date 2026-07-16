package com.bosco.mine.model.strategy;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 一次不可变策略发布在某个作用范围内提供的局部字段。
 *
 * @param releaseId 发布标识
 * @param scopeType 作用范围类型
 * @param scopeId 作用范围标识
 * @param groupPriority 设备组优先级，非设备组为空
 * @param values 已通过策略定义校验的局部字段
 */
public record ScopedStrategyFragment(
        UUID releaseId,
        StrategyScopeType scopeType,
        UUID scopeId,
        Integer groupPriority,
        Map<String, Object> values
) {

    private static final int MAX_FIELDS = 256;
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{0,127}$");

    /**
     * 冻结并校验作用域片段，避免解析期间被调用方修改。
     */
    public ScopedStrategyFragment {
        releaseId = Objects.requireNonNull(releaseId, "策略发布标识不能为空");
        scopeType = Objects.requireNonNull(scopeType, "策略作用范围不能为空");
        scopeId = Objects.requireNonNull(scopeId, "策略作用范围标识不能为空");
        if (scopeType == StrategyScopeType.GROUP) {
            if (groupPriority == null || groupPriority < 0 || groupPriority > 1000) {
                throw new IllegalArgumentException("设备组优先级必须在 0 到 1000 之间");
            }
        } else if (groupPriority != null) {
            throw new IllegalArgumentException("只有设备组作用范围可以设置组优先级");
        }
        Objects.requireNonNull(values, "策略字段不能为空");
        if (values.isEmpty() || values.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("策略片段字段数量必须在 1 到 256 之间");
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (field == null || !FIELD_NAME.matcher(field).matches()) {
                throw new IllegalArgumentException("策略字段名称不合法");
            }
            copied.put(field, normalizeScalar(value));
        });
        values = Collections.unmodifiableMap(copied);
    }

    private static Object normalizeScalar(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
        }
        throw new IllegalArgumentException("策略字段值只能使用受支持的标量类型");
    }
}
