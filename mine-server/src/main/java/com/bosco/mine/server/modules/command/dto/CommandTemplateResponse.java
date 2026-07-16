package com.bosco.mine.server.modules.command.dto;

import java.util.Map;

/**
 * 可下发命令模板响应。
 *
 * @param key 稳定模板编码
 * @param version 模板版本
 * @param riskLevel 风险级别
 * @param defaultTtlSeconds 默认 TTL 秒数
 * @param defaultTimeoutSeconds 默认回复超时秒数
 * @param parameterSchema 参数 JSON Schema
 * @param requiresReason 是否强制填写下发原因
 */
public record CommandTemplateResponse(
        String key,
        int version,
        String riskLevel,
        int defaultTtlSeconds,
        int defaultTimeoutSeconds,
        Map<String, Object> parameterSchema,
        boolean requiresReason
) {
}
