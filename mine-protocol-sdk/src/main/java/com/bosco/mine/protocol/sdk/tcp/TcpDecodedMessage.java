package com.bosco.mine.protocol.sdk.tcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 表示插件从一个完整 TCP 帧解码出的标准遥测中间结果。
 *
 * @param productKey 产品编码，仅用于可信身份反查
 * @param deviceCode 设备自然编码，仅用于可信身份反查
 * @param bootId 设备启动周期标识
 * @param sequence 启动周期内非负序号
 * @param occurredAt 设备声明的发生时间
 * @param replayed 是否为弱网补传数据
 * @param properties 已映射到平台物模型标识的属性
 */
public record TcpDecodedMessage(
        String productKey,
        String deviceCode,
        String bootId,
        Long sequence,
        Instant occurredAt,
        boolean replayed,
        Map<String, Object> properties
) {

    private static final Pattern PRODUCT_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DEVICE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final int MAX_PROPERTY_COUNT = 256;
    private static final int MAX_NESTING_DEPTH = 8;

    /**
     * 校验身份、时间和属性，并递归复制调用方提供的可变容器。
     */
    public TcpDecodedMessage {
        if (productKey == null || !PRODUCT_KEY.matcher(productKey).matches()) {
            throw new IllegalArgumentException("productKey 格式不合法");
        }
        if (deviceCode == null || !DEVICE_CODE.matcher(deviceCode).matches()) {
            throw new IllegalArgumentException("deviceCode 格式不合法");
        }
        if (bootId == null || bootId.isBlank() || bootId.length() > 128) {
            throw new IllegalArgumentException("bootId 长度必须在 1 到 128 之间");
        }
        if (sequence == null || sequence < 0) {
            throw new IllegalArgumentException("sequence 必须是非负长整数");
        }
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        Objects.requireNonNull(properties, "properties 不能为空");
        if (properties.isEmpty() || properties.size() > MAX_PROPERTY_COUNT) {
            throw new IllegalArgumentException("properties 数量必须在 1 到 256 之间");
        }
        properties = immutableObject(properties, "properties", 0);
    }

    private static Map<String, Object> immutableObject(Map<?, ?> source, String path, int depth) {
        requireDepth(depth);
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text) || text.isBlank() || text.length() > 128) {
                throw new IllegalArgumentException(path + " 的属性名必须是 1 到 128 字符");
            }
            copy.put(text, immutableValue(value, path + "." + text, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> immutableList(List<?> source, String path, int depth) {
        requireDepth(depth);
        List<Object> copy = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            copy.add(immutableValue(source.get(index), path + "[" + index + "]", depth + 1));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Object immutableValue(Object value, String path, int depth) {
        if (value == null || isScalar(value)) return value;
        if (value instanceof Map<?, ?> map) return immutableObject(map, path, depth);
        if (value instanceof List<?> list) return immutableList(list, path, depth);
        throw new IllegalArgumentException(path + " 使用了不支持的属性值类型: "
                + value.getClass().getName());
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal;
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("属性嵌套深度不能超过 " + MAX_NESTING_DEPTH);
        }
    }
}
