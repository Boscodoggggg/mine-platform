package com.bosco.mine.server.modules.command.template;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 命令模板草稿创建或修改请求。
 *
 * @param templateKey 稳定命令编码
 * @param nameZh 中文名称
 * @param nameEn 英文名称
 * @param description 命令用途说明
 * @param payloadFormat MQTT JSON 或原始十六进制格式
 * @param parameterSchema 受限参数 JSON Schema
 * @param rawPayloadHex 固定原始十六进制载荷
 * @param defaultTtlSeconds 默认排队存活秒数
 * @param defaultTimeoutSeconds 默认回复超时秒数
 * @param requiresReason 是否强制填写下发原因
 * @param productIds 绑定产品标识
 * @param version 草稿乐观锁版本
 */
public record CommandTemplateRequest(
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{2,127}")
        String templateKey,
        @NotBlank @Size(max = 128) String nameZh,
        @Size(max = 128) String nameEn,
        @Size(max = 1000) String description,
        @NotBlank @Pattern(regexp = "MQTT_JSON|RAW_HEX") String payloadFormat,
        @NotNull Map<String, Object> parameterSchema,
        String rawPayloadHex,
        @Min(1) @Max(86400) int defaultTtlSeconds,
        @Min(1) @Max(86400) int defaultTimeoutSeconds,
        boolean requiresReason,
        @NotEmpty List<UUID> productIds,
        @Min(0) long version
) {
}
