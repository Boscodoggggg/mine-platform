package com.bosco.mine.server.modules.command.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 从 PostgreSQL 读取的命令持久化行。
 *
 * @param id 命令标识
 * @param deviceId 设备标识
 * @param templateKey 模板编码
 * @param templateVersion 模板版本
 * @param parametersJson 命令参数 JSON
 * @param reason 提交原因
 * @param riskLevel 风险级别
 * @param status 命令状态
 * @param ttlSeconds TTL 秒数
 * @param timeoutSeconds 回复超时秒数
 * @param idempotencyKey 幂等键
 * @param protocolVersion 协议版本
 * @param configReleaseId 配置发布标识
 * @param createdBy 创建人
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param queuedAt 入队时间
 * @param sentAt 发送时间
 * @param deviceReceivedAt 设备收到时间
 * @param completedAt 完成时间
 * @param expiresAt 过期时间
 * @param lastErrorCode 最后错误码
 * @param replyJson 设备回复 JSON
 * @param version 乐观锁版本
 */
public record StoredCommand(
        UUID id,
        UUID deviceId,
        String templateKey,
        int templateVersion,
        String parametersJson,
        String reason,
        String riskLevel,
        String status,
        int ttlSeconds,
        int timeoutSeconds,
        String idempotencyKey,
        String protocolVersion,
        UUID configReleaseId,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant queuedAt,
        Instant sentAt,
        Instant deviceReceivedAt,
        Instant completedAt,
        Instant expiresAt,
        String lastErrorCode,
        String replyJson,
        long version
) {
}
