package com.bosco.mine.server.modules.notification.provider;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelAdapter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 封闭注册平台随版本发布的通知 Provider。
 */
@Component
public class NotificationProviderRegistry {

    private final Map<NotificationAdapterKey, NotificationChannelAdapter> adapters;
    private final List<NotificationProviderDefinition> catalog;

    /**
     * 从 Spring 内置适配器构建唯一注册表。
     *
     * @param candidates 平台编译期包含的适配器
     */
    public NotificationProviderRegistry(List<NotificationChannelAdapter> candidates) {
        Map<NotificationAdapterKey, NotificationChannelAdapter> indexed = new HashMap<>();
        List<NotificationProviderDefinition> definitions = new ArrayList<>();
        for (NotificationChannelAdapter adapter : Objects.requireNonNull(
                candidates, "通知适配器集合不能为空")) {
            NotificationAdapterKey key = Objects.requireNonNull(adapter.key(), "通知适配器键不能为空");
            NotificationProviderDefinition definition = Objects.requireNonNull(
                    adapter.definition(), "通知 Provider 定义不能为空");
            if (!key.equals(definition.key())) {
                throw new IllegalStateException("通知适配器键与 Provider 定义不一致: " + key.provider());
            }
            if (indexed.putIfAbsent(key, adapter) != null) {
                throw new IllegalStateException("通知 Provider 重复注册: " + key.provider());
            }
            definitions.add(definition);
        }
        definitions.sort((left, right) -> left.key().provider().compareTo(right.key().provider()));
        this.adapters = Map.copyOf(indexed);
        this.catalog = List.copyOf(definitions);
    }

    /**
     * 返回稳定排序的 Provider 目录。
     *
     * @return Provider 定义集合
     */
    public List<NotificationProviderDefinition> catalog() {
        return catalog;
    }

    /**
     * 获取指定内置 Provider，不接受未知或渠道类型不匹配的编码。
     *
     * @param key Provider 唯一键
     * @return 内置适配器
     */
    public NotificationChannelAdapter require(NotificationAdapterKey key) {
        NotificationChannelAdapter adapter = adapters.get(Objects.requireNonNull(
                key, "通知 Provider 键不能为空"));
        if (adapter == null) {
            throw new IllegalArgumentException("平台不支持该通知供应商或渠道类型");
        }
        return adapter;
    }

    /**
     * 校验通用字段、必填凭据和 Provider 特有组合。
     *
     * @param key Provider 唯一键
     * @param configuration 非敏感配置
     * @param credentials 解密凭据
     * @param enabled 渠道是否准备启用
     */
    public void validate(
            NotificationAdapterKey key,
            Map<String, Object> configuration,
            Map<String, String> credentials,
            boolean enabled
    ) {
        NotificationChannelAdapter adapter = require(key);
        Map<String, Object> safeConfiguration = new LinkedHashMap<>(Objects.requireNonNull(
                configuration, "通知 Provider 配置不能为空"));
        Map<String, String> safeCredentials = new LinkedHashMap<>(Objects.requireNonNull(
                credentials, "通知 Provider 凭据不能为空"));
        safeConfiguration.values().removeIf(Objects::isNull);
        safeCredentials.values().removeIf(Objects::isNull);
        NotificationProviderDefinition definition = adapter.definition();
        validateFields(definition.configurationFields(), safeConfiguration, enabled, false);
        validateFields(definition.secretFields(), safeCredentials, enabled, true);
        if (enabled) {
            adapter.validateConfiguration(Map.copyOf(safeConfiguration), Map.copyOf(safeCredentials));
        }
    }

    private void validateFields(
            List<NotificationProviderDefinition.Field> fields,
            Map<String, ?> values,
            boolean enabled,
            boolean secret
    ) {
        Map<String, NotificationProviderDefinition.Field> definitions = new HashMap<>();
        fields.forEach(field -> definitions.put(field.key(), field));
        values.keySet().forEach(key -> {
            if (!definitions.containsKey(key)) {
                throw new IllegalArgumentException("Provider 包含未知字段: " + key);
            }
        });
        fields.forEach(field -> {
            Object value = values.get(field.key());
            if (enabled && field.required() && isMissing(value)) {
                throw new IllegalArgumentException("Provider 缺少必填字段: " + field.key());
            }
            if (!isMissing(value)) {
                validateValue(field, value, secret);
            }
        });
    }

    private void validateValue(
            NotificationProviderDefinition.Field field,
            Object value,
            boolean secret
    ) {
        boolean valid = switch (field.type()) {
            case STRING, SECRET -> value instanceof String;
            case INTEGER -> value instanceof Number number && number.doubleValue() == number.intValue();
            case BOOLEAN -> value instanceof Boolean;
            case STRING_LIST -> value instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance);
        };
        if (!valid || (secret && field.type() != NotificationProviderDefinition.FieldType.SECRET)) {
            throw new IllegalArgumentException("Provider 字段类型不合法: " + field.key());
        }
        int length = value instanceof List<?> list ? list.size() : value.toString().length();
        if (field.maxLength() != null && length > field.maxLength()) {
            throw new IllegalArgumentException("Provider 字段超过长度上限: " + field.key());
        }
        if (!field.options().isEmpty() && !field.options().contains(value.toString())) {
            throw new IllegalArgumentException("Provider 字段选项不合法: " + field.key());
        }
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }
}
