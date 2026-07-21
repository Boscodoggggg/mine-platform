package com.bosco.mine.server.modules.device.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 设备接入端点分层诊断响应。
 *
 * @param deviceId      设备标识
 * @param status        总体状态
 * @param startedAt     开始时间
 * @param durationMillis 总耗时
 * @param message       脱敏结论
 * @param stages        分层结果
 */
public record DeviceConnectionTestResponse(
        UUID deviceId,
        String status,
        Instant startedAt,
        long durationMillis,
        String message,
        List<Stage> stages
) {

    /**
     * 单个诊断阶段结果。
     *
     * @param stage          阶段编码
     * @param status         通过、失败或跳过
     * @param message        脱敏说明
     * @param durationMillis 阶段耗时
     */
    public record Stage(String stage, String status, String message, long durationMillis) {
    }
}
