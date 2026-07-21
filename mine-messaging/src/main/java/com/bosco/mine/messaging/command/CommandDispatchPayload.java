package com.bosco.mine.messaging.command;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * mine-server 请求 gateway 编码并下发的版本化命令载荷。
 *
 * @param commandId 命令标识
 * @param deviceId 目标设备标识
 * @param templateKey 命令模板编码
 * @param templateVersion 命令模板版本
 * @param parameters 经模板校验的参数
 * @param riskLevel 风险等级
 * @param ttlSeconds 队列存活秒数
 * @param timeoutSeconds 设备回复超时秒数
 * @param expiresAt 命令绝对过期时间
 * @param payloadFormat MQTT JSON 或原始十六进制载荷格式
 * @param rawPayloadBase64 原始命令字节的 Base64 表示
 * @param replyExpected 是否等待设备业务回复
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandDispatchPayload(
        UUID commandId,
        UUID deviceId,
        String templateKey,
        int templateVersion,
        Map<String, Object> parameters,
        String riskLevel,
        int ttlSeconds,
        int timeoutSeconds,
        Instant expiresAt,
        String payloadFormat,
        String rawPayloadBase64,
        boolean replyExpected
) {

    /**
     * 校验命令载荷并复制参数集合。
     */
    public CommandDispatchPayload {
        Objects.requireNonNull(commandId, "命令标识不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        templateKey = requireText(templateKey, "命令模板编码");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "命令参数不能为空"));
        riskLevel = requireText(riskLevel, "风险等级");
        Objects.requireNonNull(expiresAt, "命令过期时间不能为空");
        if (templateVersion < 1 || ttlSeconds < 1 || timeoutSeconds < 1
                || timeoutSeconds > ttlSeconds) {
            throw new IllegalArgumentException("命令版本、TTL 或回复超时不合法");
        }
        if (payloadFormat == null) {
            payloadFormat = "MQTT_JSON";
            replyExpected = true;
        }
        payloadFormat = requireText(payloadFormat, "载荷格式");
        if ("MQTT_JSON".equals(payloadFormat)) {
            if (rawPayloadBase64 != null || !replyExpected) {
                throw new IllegalArgumentException("MQTT JSON 命令载荷配置不合法");
            }
        } else if ("RAW_HEX".equals(payloadFormat)) {
            byte[] raw = decodeRaw(rawPayloadBase64);
            if (raw.length > 4096 || replyExpected || !parameters.isEmpty()) {
                throw new IllegalArgumentException("原始命令载荷配置不合法");
            }
        } else {
            throw new IllegalArgumentException("命令载荷格式不受支持: " + payloadFormat);
        }
    }

    /**
     * 创建兼容既有 MQTT JSON 命令的载荷。
     */
    public CommandDispatchPayload(
            UUID commandId,
            UUID deviceId,
            String templateKey,
            int templateVersion,
            Map<String, Object> parameters,
            String riskLevel,
            int ttlSeconds,
            int timeoutSeconds,
            Instant expiresAt
    ) {
        this(commandId, deviceId, templateKey, templateVersion, parameters, riskLevel,
                ttlSeconds, timeoutSeconds, expiresAt, "MQTT_JSON", null, true);
    }

    /**
     * 兼容反序列化尚未携带载荷格式和回复标记的历史 Outbox 消息。
     *
     * @return 完成默认值补齐并通过统一校验的命令载荷
     */
    @JsonCreator
    public static CommandDispatchPayload fromJson(
            @JsonProperty("commandId") UUID commandId,
            @JsonProperty("deviceId") UUID deviceId,
            @JsonProperty("templateKey") String templateKey,
            @JsonProperty("templateVersion") int templateVersion,
            @JsonProperty("parameters") Map<String, Object> parameters,
            @JsonProperty("riskLevel") String riskLevel,
            @JsonProperty("ttlSeconds") int ttlSeconds,
            @JsonProperty("timeoutSeconds") int timeoutSeconds,
            @JsonProperty("expiresAt") Instant expiresAt,
            @JsonProperty("payloadFormat") String payloadFormat,
            @JsonProperty("rawPayloadBase64") String rawPayloadBase64,
            @JsonProperty("replyExpected") Boolean replyExpected
    ) {
        String effectiveFormat = payloadFormat == null ? "MQTT_JSON" : payloadFormat;
        boolean effectiveReplyExpected = replyExpected == null
                ? !"RAW_HEX".equals(effectiveFormat) : replyExpected;
        return new CommandDispatchPayload(
                commandId, deviceId, templateKey, templateVersion, parameters, riskLevel,
                ttlSeconds, timeoutSeconds, expiresAt, effectiveFormat,
                rawPayloadBase64, effectiveReplyExpected
        );
    }

    /**
     * 返回原始命令字节副本。
     *
     * @return 原始字节；JSON 命令返回空数组
     */
    public byte[] decodedRawPayload() {
        return "RAW_HEX".equals(payloadFormat) ? decodeRaw(rawPayloadBase64) : new byte[0];
    }

    private static byte[] decodeRaw(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("原始命令载荷不能为空");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0) {
                throw new IllegalArgumentException("原始命令载荷不能为空");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("原始命令载荷不是合法 Base64", exception);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }
}
