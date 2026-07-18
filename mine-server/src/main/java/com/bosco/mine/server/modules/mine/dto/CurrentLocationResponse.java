package com.bosco.mine.server.modules.mine.dto;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个矿区的有界当前位置快照。
 *
 * @param siteId 矿区标识
 * @param watermark 本次快照包含的最大摄取序号
 * @param source 数据来源，Redis 或 TimescaleDB
 * @param truncated 是否因数量上限截断
 * @param items 当前位置条目
 */
public record CurrentLocationResponse(
        UUID siteId,
        long watermark,
        String source,
        boolean truncated,
        List<Item> items
) {
    /** 创建不可变且有界的当前位置响应。 */
    public CurrentLocationResponse {
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        if (watermark < 0L || source == null || source.isBlank()) {
            throw new IllegalArgumentException("当前位置快照元数据不合法");
        }
        items = List.copyOf(Objects.requireNonNull(items, "当前位置条目不能为空"));
    }

    /**
     * 不包含人员敏感字段的当前位置条目。
     *
     * @param eventId 位置事实事件标识
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param sourceDeviceId 可信来源设备标识
     * @param tagId 定位标签标识
     * @param tagCode 定位标签编码
     * @param coordinateSystemId 统一坐标系标识
     * @param vehicleId 发生时车辆标识
     * @param personId 发生时人员标识
     * @param ingestSequence 平台摄取序号
     * @param occurredAt 发生时间
     * @param receivedAt 接收时间
     * @param localX 统一坐标 X
     * @param localY 统一坐标 Y
     * @param localZ 统一坐标 Z
     * @param speedMetersPerSecond 速度
     * @param headingDegrees 航向
     * @param accuracyMeters 精度半径
     * @param qualityStatus 质量状态
     * @param qualityReason 降级原因
     */
    public record Item(
            UUID eventId,
            UUID organizationId,
            UUID siteId,
            UUID sourceDeviceId,
            UUID tagId,
            String tagCode,
            UUID coordinateSystemId,
            UUID vehicleId,
            UUID personId,
            long ingestSequence,
            Instant occurredAt,
            Instant receivedAt,
            double localX,
            double localY,
            Double localZ,
            Double speedMetersPerSecond,
            Double headingDegrees,
            Double accuracyMeters,
            RealtimeLocationEvent.QualityStatus qualityStatus,
            String qualityReason
    ) {
        /** 从已校验实时位置契约创建查询条目。 */
        public static Item from(RealtimeLocationEvent event) {
            Objects.requireNonNull(event, "实时位置事件不能为空");
            return new Item(
                    event.eventId(), event.organizationId(), event.siteId(),
                    event.sourceDeviceId(), event.tagId(), event.tagCode(),
                    event.coordinateSystemId(), event.vehicleId(), event.personId(),
                    event.sourceSequence(), event.occurredAt(), event.receivedAt(),
                    event.localX(), event.localY(), event.localZ(),
                    event.speedMetersPerSecond(), event.headingDegrees(),
                    event.accuracyMeters(), event.qualityStatus(), event.qualityReason());
        }
    }
}
