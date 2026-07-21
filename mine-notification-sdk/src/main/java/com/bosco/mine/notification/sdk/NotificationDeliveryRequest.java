package com.bosco.mine.notification.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 传递给通知适配器的不可变投递请求。
 *
 * @param deliveryId       平台投递标识
 * @param idempotencyKey   稳定幂等键
 * @param recipient        渠道目标，适配器不得记录原文
 * @param subject          已渲染标题
 * @param body             已渲染正文
 * @param templateVariables 受控模板变量
 * @param configuration    当前渠道配置快照
 */
public record NotificationDeliveryRequest(
        UUID deliveryId,
        String idempotencyKey,
        String recipient,
        String subject,
        String body,
        Map<String, Object> templateVariables,
        Map<String, Object> configuration
) {
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final int MAX_TARGET_LENGTH = 2_048;
    private static final int MAX_SUBJECT_LENGTH = 512;
    private static final int MAX_BODY_LENGTH = 16_384;
    private static final int MAX_MAP_ENTRIES = 128;
    private static final int MAX_DEPTH = 8;

    /**
     * 校验请求边界并递归冻结变量和配置。
     */
    public NotificationDeliveryRequest {
        Objects.requireNonNull(deliveryId, "通知投递标识不能为空");
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("通知投递幂等键格式不合法");
        }
        recipient = requireText(recipient, MAX_TARGET_LENGTH, "通知接收目标");
        subject = requireText(subject, MAX_SUBJECT_LENGTH, "通知标题");
        body = requireText(body, MAX_BODY_LENGTH, "通知正文");
        templateVariables = immutableMap(templateVariables, "模板变量", 0);
        configuration = immutableMap(configuration, "渠道配置", 0);
    }

    private static String requireText(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过 " + maxLength);
        }
        return value.strip();
    }

    private static Map<String, Object> immutableMap(
            Map<String, ?> source,
            String label,
            int depth
    ) {
        Objects.requireNonNull(source, label + "不能为空");
        if (source.size() > MAX_MAP_ENTRIES) {
            throw new IllegalArgumentException(label + "字段数量超过上限");
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(label + "嵌套深度超过上限");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 128) {
                throw new IllegalArgumentException(label + "字段名格式不合法");
            }
            copy.put(key, immutableValue(value, label, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, String label, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(label + "嵌套深度超过上限");
        }
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stringMap = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException(label + "对象字段名必须是字符串");
                }
                stringMap.put(text, nested);
            });
            return immutableMap(stringMap, label, depth);
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAX_MAP_ENTRIES) {
                throw new IllegalArgumentException(label + "数组元素数量超过上限");
            }
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item, label, depth + 1)));
            return List.copyOf(copy);
        }
        throw new IllegalArgumentException(label + "包含不受支持的值类型");
    }
}
