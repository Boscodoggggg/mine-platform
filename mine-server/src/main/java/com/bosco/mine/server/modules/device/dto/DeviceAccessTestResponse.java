package com.bosco.mine.server.modules.device.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 设备四层接入手动检查结果。
 *
 * @param deviceId 设备标识
 * @param status 总体状态
 * @param checkedAt 检查时间
 * @param durationMillis 总耗时
 * @param message 稳定结论码
 * @param stages 各层检查结果
 */
public record DeviceAccessTestResponse(
        UUID deviceId,
        String status,
        Instant checkedAt,
        long durationMillis,
        String message,
        List<Stage> stages
) {

    /**
     * 单层接入检查结果。
     *
     * @param layer 接入层级
     * @param status 检查状态
     * @param message 稳定原因码
     * @param observedAt 证据时间
     * @param durationMillis 检查耗时
     */
    public record Stage(
            String layer,
            String status,
            String message,
            Instant observedAt,
            long durationMillis
    ) {
    }
}
