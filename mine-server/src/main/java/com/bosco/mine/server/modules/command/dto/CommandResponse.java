package com.bosco.mine.server.modules.command.dto;

import com.bosco.mine.server.modules.command.model.CommandInstance;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 命令实例及完整状态时间线响应。
 *
 * @param id 命令标识
 * @param deviceId 设备标识
 * @param templateKey 模板编码
 * @param templateVersion 模板版本
 * @param parameters 已固化参数
 * @param reason 下发原因
 * @param workOrderNo 作业单号
 * @param cancelledBy 取消人标识
 * @param cancelReason 取消原因
 * @param riskLevel 风险级别
 * @param status 当前状态
 * @param ttlSeconds 排队存活秒数
 * @param timeoutSeconds 回复超时秒数
 * @param idempotencyKey 创建幂等键
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param queuedAt 入队时间
 * @param sentAt 发送确认时间
 * @param deviceReceivedAt 设备收到时间
 * @param completedAt 完成时间
 * @param expiresAt 过期时间
 * @param lastErrorCode 最后错误码
 * @param reply 设备回复
 * @param version 状态版本
 */
public record CommandResponse(
        UUID id,
        UUID deviceId,
        String templateKey,
        int templateVersion,
        Map<String, Object> parameters,
        String reason,
        String workOrderNo,
        UUID cancelledBy,
        String cancelReason,
        String riskLevel,
        String status,
        int ttlSeconds,
        int timeoutSeconds,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        Instant queuedAt,
        Instant sentAt,
        Instant deviceReceivedAt,
        Instant completedAt,
        Instant expiresAt,
        String lastErrorCode,
        Map<String, Object> reply,
        long version
) {
    /**
     * 从领域快照创建接口响应。
     *
     * @param command 命令领域快照
     * @return 命令响应
     */
    public static CommandResponse from(CommandInstance command) {
        return new CommandResponse(
                command.id(), command.deviceId(), command.templateKey(), command.templateVersion(),
                command.parameters(), command.reason(), command.workOrderNo(), command.cancelledBy(),
                command.cancelReason(), command.riskLevel().name(), command.status().name(),
                command.ttlSeconds(), command.timeoutSeconds(), command.idempotencyKey(),
                command.createdAt(), command.updatedAt(), command.queuedAt(), command.sentAt(),
                command.deviceReceivedAt(), command.completedAt(), command.expiresAt(),
                command.lastErrorCode(), command.reply(), command.version()
        );
    }
}
