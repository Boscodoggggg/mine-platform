package com.bosco.mine.server.modules.command.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * 命令下发请求。
 *
 * @param deviceId 目标设备标识
 * @param templateKey 命令模板编码
 * @param parameters 模板参数
 * @param reason 下发原因
 * @param workOrderNo 作业单号
 * @param ttlSeconds 排队存活秒数
 * @param timeoutSeconds 设备回复超时秒数
 */
public record CommandCreateRequest(
        @NotNull UUID deviceId,
        @NotBlank @Size(max = 128) String templateKey,
        @NotNull Map<String, Object> parameters,
        @Size(max = 1000) String reason,
        @Size(max = 128) String workOrderNo,
        @Min(1) @Max(86400) int ttlSeconds,
        @Min(1) @Max(86400) int timeoutSeconds
) {
}
