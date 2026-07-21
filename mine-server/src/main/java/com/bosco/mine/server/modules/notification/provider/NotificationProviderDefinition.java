package com.bosco.mine.server.modules.notification.provider;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 描述一个平台内置通知 Provider 的受控配置字段。
 *
 * @param key Provider 唯一键
 * @param displayNameKey Provider 展示名称国际化键
 * @param supportsDeliveryReceipt 是否支持终端级异步回执
 * @param configurationFields 非敏感配置字段
 * @param secretFields 加密凭据字段
 */
public record NotificationProviderDefinition(
        NotificationAdapterKey key,
        String displayNameKey,
        boolean supportsDeliveryReceipt,
        List<Field> configurationFields,
        List<Field> secretFields
) {
    private static final Pattern I18N_KEY = Pattern.compile("^[a-z][a-zA-Z0-9.-]{2,127}$");

    /**
     * 校验 Provider 元数据并冻结字段集合。
     */
    public NotificationProviderDefinition {
        Objects.requireNonNull(key, "通知 Provider 键不能为空");
        if (displayNameKey == null || !I18N_KEY.matcher(displayNameKey).matches()) {
            throw new IllegalArgumentException("通知 Provider 国际化键格式不合法");
        }
        configurationFields = List.copyOf(Objects.requireNonNull(
                configurationFields, "Provider 配置字段不能为空"));
        secretFields = List.copyOf(Objects.requireNonNull(
                secretFields, "Provider 凭据字段不能为空"));
        Set<String> keys = new HashSet<>();
        configurationFields.forEach(field -> {
            if (field.type() == FieldType.SECRET || !keys.add(field.key())) {
                throw new IllegalArgumentException("Provider 配置字段定义重复或类型不合法");
            }
        });
        secretFields.forEach(field -> {
            if (field.type() != FieldType.SECRET || !keys.add(field.key())) {
                throw new IllegalArgumentException("Provider 凭据字段定义重复或类型不合法");
            }
        });
    }

    /**
     * 描述一个 Provider 配置字段。
     *
     * @param key 稳定字段编码
     * @param type 字段数据类型
     * @param required 启用渠道时是否必填
     * @param maxLength 字符串或数组最大长度
     * @param defaultValue 建议默认值
     * @param options 允许的固定选项
     */
    public record Field(
            String key,
            FieldType type,
            boolean required,
            Integer maxLength,
            Object defaultValue,
            List<String> options
    ) {
        private static final Pattern FIELD_KEY = Pattern.compile("^[a-z][a-zA-Z0-9]{1,63}$");

        /**
         * 校验并冻结字段定义。
         */
        public Field {
            if (key == null || !FIELD_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Provider 字段编码格式不合法");
            }
            Objects.requireNonNull(type, "Provider 字段类型不能为空");
            if (maxLength != null && (maxLength < 1 || maxLength > 32768)) {
                throw new IllegalArgumentException("Provider 字段长度上限不合法");
            }
            options = List.copyOf(Objects.requireNonNull(options, "Provider 字段选项不能为空"));
        }
    }

    /**
     * Provider 配置页面支持的稳定字段类型。
     */
    public enum FieldType {
        /** 单行或多行字符串。 */
        STRING,
        /** 整数。 */
        INTEGER,
        /** 布尔值。 */
        BOOLEAN,
        /** 字符串数组。 */
        STRING_LIST,
        /** 只写且加密保存的敏感字符串。 */
        SECRET
    }
}
