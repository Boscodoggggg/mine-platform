package com.bosco.mine.server.modules.command.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的设备下行命令快照。
 *
 * @param id 命令标识
 * @param deviceId 目标设备标识
 * @param templateKey 命令模板编码
 * @param templateVersion 命令模板版本
 * @param parameters 已校验命令参数
 * @param reason 提交原因
 * @param riskLevel 风险级别
 * @param status 当前状态
 * @param ttlSeconds 队列存活秒数
 * @param timeoutSeconds 设备回复超时秒数
 * @param idempotencyKey 创建幂等键
 * @param protocolVersion 预期协议版本
 * @param configReleaseId 预期配置发布标识
 * @param createdBy 创建人标识
 * @param createdAt 创建时间
 * @param updatedAt 最后变更时间
 * @param queuedAt 入队时间
 * @param sentAt 发送确认时间
 * @param deviceReceivedAt 设备收到时间
 * @param completedAt 进入终态时间
 * @param expiresAt TTL 截止时间
 * @param lastErrorCode 最后错误码
 * @param reply 脱敏业务回复
 * @param version 乐观锁版本
 */
public record CommandInstance(
        UUID id,
        UUID deviceId,
        String templateKey,
        int templateVersion,
        Map<String, Object> parameters,
        String reason,
        CommandRiskLevel riskLevel,
        CommandStatus status,
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
        Map<String, Object> reply,
        long version
) {

    /**
     * 创建命令快照并复制可变参数集合。
     */
    public CommandInstance {
        id = Objects.requireNonNull(id, "命令标识不能为空");
        deviceId = Objects.requireNonNull(deviceId, "设备标识不能为空");
        templateKey = requireText(templateKey, "命令模板编码");
        if (templateVersion < 1) throw new IllegalArgumentException("命令模板版本必须大于 0");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "命令参数不能为空"));
        riskLevel = Objects.requireNonNull(riskLevel, "风险级别不能为空");
        status = Objects.requireNonNull(status, "命令状态不能为空");
        if (ttlSeconds < 1 || ttlSeconds > 86400) throw new IllegalArgumentException("TTL 必须为 1 至 86400 秒");
        if (timeoutSeconds < 1 || timeoutSeconds > ttlSeconds) {
            throw new IllegalArgumentException("回复超时必须大于 0 且不超过 TTL");
        }
        idempotencyKey = requireText(idempotencyKey, "幂等键");
        createdBy = Objects.requireNonNull(createdBy, "创建人不能为空");
        createdAt = Objects.requireNonNull(createdAt, "创建时间不能为空");
        updatedAt = Objects.requireNonNull(updatedAt, "更新时间不能为空");
        expiresAt = Objects.requireNonNull(expiresAt, "过期时间不能为空");
        reply = reply == null ? null : Map.copyOf(reply);
        if (version < 0) throw new IllegalArgumentException("版本号不能小于 0");
    }

    /**
     * 创建首个低风险命令模板的实例。
     *
     * @param id 命令标识
     * @param deviceId 设备标识
     * @param templateKey 模板编码
     * @param parameters 命令参数
     * @param idempotencyKey 幂等键
     * @param createdBy 创建人标识
     * @param createdAt 创建时间
     * @param ttlSeconds TTL 秒数
     * @param timeoutSeconds 超时秒数
     * @return CREATED 状态命令
     */
    public static CommandInstance createLowRisk(
            UUID id,
            UUID deviceId,
            String templateKey,
            Map<String, Object> parameters,
            String idempotencyKey,
            UUID createdBy,
            Instant createdAt,
            int ttlSeconds,
            int timeoutSeconds
    ) {
        return new CommandInstance(
                id, deviceId, templateKey, 1, parameters, null, CommandRiskLevel.LOW,
                CommandStatus.CREATED, ttlSeconds, timeoutSeconds, idempotencyKey,
                null, null, createdBy, createdAt, createdAt,
                null, null, null, null, createdAt.plusSeconds(ttlSeconds),
                null, null, 0
        );
    }

    /**
     * 按状态机创建新快照，并写入该状态对应的时间点。
     *
     * @param target 目标状态
     * @param occurredAt 迁移发生时间
     * @return 迁移后命令快照
     */
    public CommandInstance transitionTo(CommandStatus target, Instant occurredAt) {
        status.requireTransitionTo(target);
        Objects.requireNonNull(occurredAt, "迁移时间不能为空");
        if (occurredAt.isBefore(updatedAt)) throw new IllegalArgumentException("迁移时间不能早于上次更新时间");
        return new CommandInstance(
                id, deviceId, templateKey, templateVersion, parameters, reason, riskLevel,
                target, ttlSeconds, timeoutSeconds, idempotencyKey, protocolVersion,
                configReleaseId, createdBy, createdAt, occurredAt,
                target == CommandStatus.QUEUED ? occurredAt : queuedAt,
                target == CommandStatus.SENT ? occurredAt : sentAt,
                target == CommandStatus.DEVICE_RECEIVED ? occurredAt : deviceReceivedAt,
                target.terminal() ? occurredAt : completedAt,
                expiresAt, lastErrorCode, reply, version + 1
        );
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + "不能为空");
        if (value.isBlank()) throw new IllegalArgumentException(label + "不能为空白");
        return value.strip();
    }
}
