package com.bosco.mine.server.modules.device.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备持续接入状态。
 *
 * @param deviceId 设备标识
 * @param status 总体状态
 * @param firstSeenAt 有效首帧接收时间
 * @param lastReceivedAt 最近有效报文接收时间
 * @param offlineThresholdSeconds 离线判定阈值
 * @param message 稳定原因码
 */
public record DeviceAccessStatusResponse(
        UUID deviceId,
        String status,
        Instant firstSeenAt,
        Instant lastReceivedAt,
        Integer offlineThresholdSeconds,
        String message
) {
}
