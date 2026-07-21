package com.bosco.mine.server.modules.device.dto;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 设备单个属性的最新遥测值。
 *
 * @param propertyId 属性标识
 * @param value 数值
 * @param occurredAt 设备声明的发生时间
 * @param receivedAt 平台接收时间
 * @param quality 数据质量
 * @param eventId 事件标识
 * @param bootId 设备启动周期标识
 * @param sequence 启动周期内序号
 * @param source 数据来源，取值为 REDIS 或 TIMESCALEDB
 */
public record TelemetryLatestItemResponse(
        String propertyId,
        double value,
        Instant occurredAt,
        Instant receivedAt,
        Quality quality,
        UUID eventId,
        String bootId,
        Long sequence,
        String source
) {

    /**
     * 创建并校验最新遥测响应。
     */
    public TelemetryLatestItemResponse {
        propertyId = Objects.requireNonNull(propertyId, "属性标识不能为空");
        occurredAt = Objects.requireNonNull(occurredAt, "发生时间不能为空");
        receivedAt = Objects.requireNonNull(receivedAt, "接收时间不能为空");
        quality = Objects.requireNonNull(quality, "数据质量不能为空");
        eventId = Objects.requireNonNull(eventId, "事件标识不能为空");
        source = Objects.requireNonNull(source, "数据来源不能为空");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("遥测值必须是有限数值");
        }
    }
}
