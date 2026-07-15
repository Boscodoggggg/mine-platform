package com.bosco.mine.messaging.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
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
        Instant expiresAt
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
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }
}
