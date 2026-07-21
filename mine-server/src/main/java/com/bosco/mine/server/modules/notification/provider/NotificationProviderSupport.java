package com.bosco.mine.server.modules.notification.provider;

import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 提供内置通知 Provider 共用的受控取值、字段定义和签名函数。
 */
public final class NotificationProviderSupport {

    private NotificationProviderSupport() {
    }

    /** 创建普通字符串字段定义。 */
    public static NotificationProviderDefinition.Field stringField(
            String key, boolean required, int maxLength, String defaultValue, String... options
    ) {
        return new NotificationProviderDefinition.Field(
                key, NotificationProviderDefinition.FieldType.STRING, required,
                maxLength, defaultValue, List.of(options));
    }

    /** 创建整数配置字段定义。 */
    public static NotificationProviderDefinition.Field integerField(
            String key, boolean required, Integer defaultValue
    ) {
        return new NotificationProviderDefinition.Field(
                key, NotificationProviderDefinition.FieldType.INTEGER, required,
                null, defaultValue, List.of());
    }

    /** 创建布尔配置字段定义。 */
    public static NotificationProviderDefinition.Field booleanField(
            String key, boolean required, Boolean defaultValue
    ) {
        return new NotificationProviderDefinition.Field(
                key, NotificationProviderDefinition.FieldType.BOOLEAN, required,
                null, defaultValue, List.of());
    }

    /** 创建字符串数组字段定义。 */
    public static NotificationProviderDefinition.Field stringListField(
            String key, boolean required, int maxItems
    ) {
        return new NotificationProviderDefinition.Field(
                key, NotificationProviderDefinition.FieldType.STRING_LIST, required,
                maxItems, List.of(), List.of());
    }

    /** 创建加密凭据字段定义。 */
    public static NotificationProviderDefinition.Field secretField(
            String key, boolean required, int maxLength
    ) {
        return new NotificationProviderDefinition.Field(
                key, NotificationProviderDefinition.FieldType.SECRET, required,
                maxLength, null, List.of());
    }

    /** 读取必填字符串配置。 */
    public static String requiredString(Map<String, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Provider 缺少字符串字段: " + key);
        }
        return text.strip();
    }

    /** 读取可选字符串配置。 */
    public static String optionalString(Map<String, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Provider 字段类型不合法: " + key);
        }
        return text.isBlank() ? null : text.strip();
    }

    /** 读取带默认值的整数配置。 */
    public static int integer(Map<String, ?> values, String key, int defaultValue) {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException("Provider 字段类型不合法: " + key);
        }
        return number.intValue();
    }

    /** 读取带默认值的布尔配置。 */
    public static boolean bool(Map<String, ?> values, String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("Provider 字段类型不合法: " + key);
        }
        return flag;
    }

    /** 读取字符串数组配置。 */
    public static List<String> stringList(Map<String, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("Provider 字段类型不合法: " + key);
        }
        return list.stream().map(String.class::cast).map(String::strip).toList();
    }

    /** 读取投递请求中的解密凭据。 */
    public static String requiredCredential(NotificationDeliveryRequest request, String key) {
        return requiredString(request.credentials(), key);
    }

    /** 计算 HMAC-SHA256 并返回标准 Base64。 */
    public static String hmacBase64(String data, String secret) {
        return Base64.getEncoder().encodeToString(hmac(data, secret));
    }

    /** 计算 HMAC-SHA256 并返回小写十六进制。 */
    public static String hmacHex(String data, String secret) {
        return HexFormat.of().formatHex(hmac(data, secret));
    }

    private static byte[] hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行时不支持 HMAC-SHA256", exception);
        }
    }
}
