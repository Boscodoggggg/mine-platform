package com.bosco.mine.messaging.realtime;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 遥测持久化成功后发布给实时通道的设备最新值事件。
 *
 * @param eventId 原始遥测事件标识
 * @param deviceId 设备标识
 * @param sourceSequence TimescaleDB 已提交摄取序号
 * @param occurredAt 设备发生时间
 * @param receivedAt 平台接收时间
 * @param quality 数据质量
 * @param bootId 设备启动周期标识
 * @param deviceSequence 启动周期内设备序号
 * @param values 本次成为最新值的数值属性
 */
public record RealtimeTelemetryEvent(
        UUID eventId,
        UUID deviceId,
        long sourceSequence,
        Instant occurredAt,
        Instant receivedAt,
        Quality quality,
        String bootId,
        Long deviceSequence,
        Map<String, Double> values
) {

    /**
     * 创建不可变实时遥测事件并校验数值。
     */
    public RealtimeTelemetryEvent {
        eventId = Objects.requireNonNull(eventId, "事件标识不能为空");
        deviceId = Objects.requireNonNull(deviceId, "设备标识不能为空");
        occurredAt = Objects.requireNonNull(occurredAt, "发生时间不能为空");
        receivedAt = Objects.requireNonNull(receivedAt, "接收时间不能为空");
        quality = Objects.requireNonNull(quality, "数据质量不能为空");
        if (sourceSequence < 1L) {
            throw new IllegalArgumentException("摄取序号必须大于 0");
        }
        Map<String, Double> copied = new LinkedHashMap<>();
        Objects.requireNonNull(values, "属性值不能为空").forEach((propertyId, value) -> {
            if (propertyId == null || propertyId.isBlank()) {
                throw new IllegalArgumentException("属性标识不能为空");
            }
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("实时属性值必须是有限数值");
            }
            copied.put(propertyId, value);
        });
        if (copied.isEmpty()) {
            throw new IllegalArgumentException("实时属性值不能为空");
        }
        values = Map.copyOf(copied);
    }
}
