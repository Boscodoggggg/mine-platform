package com.bosco.mine.model.strategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 规范化并编码策略定义允许的受控 JSON 值。
 *
 * <p>对象键稳定排序，数组保持业务顺序，十进制去除无意义尾零。该类型只服务策略包内部，
 * 不接受任意 Java 对象。</p>
 */
final class StrategyValueNormalizer {

    private StrategyValueNormalizer() {
    }

    /**
     * 深拷贝并冻结受控策略值。
     *
     * @param value 已由具体策略定义校验的值
     * @return 规范化不可变值
     */
    static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            list.forEach(item -> normalized.add(normalize(item)));
            return Collections.unmodifiableList(normalized);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new TreeMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text) || text.isEmpty()) {
                    throw new IllegalArgumentException("策略结构化字段的对象键必须是非空字符串");
                }
                normalized.put(text, normalize(item));
            });
            return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
        }
        throw new IllegalArgumentException("策略字段值包含不受支持的类型");
    }

    /**
     * 生成带类型和长度边界的稳定结构键。
     *
     * @param value 受控策略值
     * @return 可用于比较与摘要的无歧义结构键
     */
    static String canonicalKey(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "string:" + text.length() + ":" + text;
        if (value instanceof Boolean bool) return "boolean:" + bool;
        if (value instanceof Integer integer) return "integer:" + integer;
        if (value instanceof Long number) return "long:" + number;
        if (value instanceof BigDecimal decimal) {
            BigDecimal normalized = decimal.signum() == 0
                    ? BigDecimal.ZERO
                    : decimal.stripTrailingZeros();
            return "decimal:" + normalized.toPlainString();
        }
        if (value instanceof List<?> list) {
            StringBuilder key = new StringBuilder("list:").append(list.size()).append(':');
            list.forEach(item -> appendFramed(key, canonicalKey(item)));
            return key.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((field, item) -> {
                if (!(field instanceof String text) || text.isEmpty()) {
                    throw new IllegalArgumentException("策略结构化字段的对象键必须是非空字符串");
                }
                ordered.put(text, item);
            });
            StringBuilder key = new StringBuilder("map:").append(ordered.size()).append(':');
            ordered.forEach((field, item) -> {
                appendFramed(key, field);
                appendFramed(key, canonicalKey(item));
            });
            return key.toString();
        }
        throw new IllegalArgumentException("策略字段值包含不受支持的类型");
    }

    private static void appendFramed(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
