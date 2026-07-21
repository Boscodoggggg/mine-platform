package com.bosco.mine.messaging.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 描述 gateway 观察到的命令传输或设备业务回复状态。
 *
 * @param commandId 命令标识
 * @param deviceId 设备标识
 * @param status 命令目标状态
 * @param occurredAt 状态发生时间
 * @param deviceReceivedAt 设备业务 ACK 时间，传输状态可为空
 * @param reply 设备回复的结构化内容
 * @param errorCode 设备拒绝或执行失败错误码，可为空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandStatusPayload(
        UUID commandId,
        UUID deviceId,
        String status,
        Instant occurredAt,
        Instant deviceReceivedAt,
        Map<String, Object> reply,
        String errorCode
) {
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "SENT", "PUBLISHED", "DEVICE_RECEIVED", "SUCCEEDED", "REFUSED", "FAILED", "EXPIRED", "TIMEOUT"
    );

    /**
     * 创建并校验命令状态载荷。
     */
    public CommandStatusPayload {
        Objects.requireNonNull(commandId, "命令标识不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("命令状态不受支持: " + status);
        }
        Objects.requireNonNull(occurredAt, "状态发生时间不能为空");
        reply = reply == null ? Map.of() : Map.copyOf(reply);
        if (errorCode != null && errorCode.isBlank()) {
            throw new IllegalArgumentException("错误码不能为空白字符串");
        }
    }
}
