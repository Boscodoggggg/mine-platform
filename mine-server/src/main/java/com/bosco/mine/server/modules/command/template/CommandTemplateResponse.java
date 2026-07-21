package com.bosco.mine.server.modules.command.template;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 命令模板及当前版本响应。
 *
 * @param templateId 稳定模板标识
 * @param versionId 模板版本标识
 * @param templateKey 稳定模板编码
 * @param versionNumber 模板版本号
 * @param status 模板版本状态
 * @param nameZh 中文名称
 * @param nameEn 英文名称
 * @param description 用途说明
 * @param riskLevel 风险等级
 * @param payloadFormat 载荷格式
 * @param parameterSchema 参数 Schema
 * @param rawPayloadHex 固定原始载荷
 * @param payloadSha256 原始载荷摘要
 * @param defaultTtlSeconds 默认 TTL
 * @param defaultTimeoutSeconds 默认回复超时
 * @param requiresReason 是否强制下发原因
 * @param replyExpected 是否等待设备回复
 * @param productIds 绑定产品
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param publishedAt 发布时间
 * @param version 乐观锁版本
 */
public record CommandTemplateResponse(
        UUID templateId,
        UUID versionId,
        String templateKey,
        int versionNumber,
        String status,
        String nameZh,
        String nameEn,
        String description,
        String riskLevel,
        String payloadFormat,
        Map<String, Object> parameterSchema,
        String rawPayloadHex,
        String payloadSha256,
        int defaultTtlSeconds,
        int defaultTimeoutSeconds,
        boolean requiresReason,
        boolean replyExpected,
        List<UUID> productIds,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        long version
) {
}
