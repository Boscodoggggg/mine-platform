package com.bosco.mine.model.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 表示已经映射到物模型属性标识的遥测值集合。
 *
 * @param values 属性标识到标准值的映射
 */
public record TelemetryPayload(Map<String, Object> values) {

    /**
     * 创建遥测载荷并递归隔离调用方提供的可变对象和数组。
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public TelemetryPayload {
        values = immutableObject(Objects.requireNonNull(values, "遥测属性不能为空"), "properties");
    }

    /**
     * 以普通 JSON 对象形式输出遥测属性。
     *
     * @return 不可修改的遥测属性
     */
    @Override
    @JsonValue
    public Map<String, Object> values() {
        return values;
    }

    private static Map<String, Object> immutableObject(Map<?, ?> source, String path) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                throw new IllegalArgumentException(path + " 的字段名必须是非空字符串");
            }
            copy.put(stringKey, immutableValue(value, path + "." + stringKey));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> immutableArray(List<?> source, String path) {
        List<Object> copy = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            copy.add(immutableValue(source.get(index), path + "[" + index + "]"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Object immutableValue(Object value, String path) {
        if (value == null || isImmutableScalar(value)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return immutableObject(map, path);
        }
        if (value instanceof List<?> list) {
            return immutableArray(list, path);
        }
        throw new IllegalArgumentException(path + " 使用了不支持的遥测值类型: "
                + value.getClass().getName());
    }

    private static boolean isImmutableScalar(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal;
    }
}
