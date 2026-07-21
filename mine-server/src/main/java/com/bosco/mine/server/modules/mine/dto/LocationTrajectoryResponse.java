package com.bosco.mine.server.modules.mine.dto;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用固定水位和查询绑定游标返回的一页矿区轨迹。
 *
 * @param siteId 矿区标识
 * @param subjectType 查询对象类型
 * @param subjectId 查询对象标识
 * @param from 开始时间，包含
 * @param to 结束时间，不包含
 * @param watermark 固定摄取水位
 * @param nextCursor 下一页不透明游标
 * @param sampled 是否执行服务端抽稀
 * @param sourcePointCount 本页抽稀前原始点数
 * @param items 轨迹点
 */
public record LocationTrajectoryResponse(
        UUID siteId,
        String subjectType,
        UUID subjectId,
        Instant from,
        Instant to,
        long watermark,
        String nextCursor,
        boolean sampled,
        int sourcePointCount,
        List<Point> items
) {
    /** 创建不可变轨迹响应。 */
    public LocationTrajectoryResponse {
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        Objects.requireNonNull(subjectType, "轨迹对象类型不能为空");
        Objects.requireNonNull(subjectId, "轨迹对象标识不能为空");
        Objects.requireNonNull(from, "轨迹开始时间不能为空");
        Objects.requireNonNull(to, "轨迹结束时间不能为空");
        if (watermark < 0L || sourcePointCount < 0) {
            throw new IllegalArgumentException("轨迹响应水位或点数不合法");
        }
        items = List.copyOf(Objects.requireNonNull(items, "轨迹点不能为空"));
    }

    /**
     * 双坐标事实中的统一坐标轨迹点。
     *
     * @param eventId 位置事实事件标识
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param sourceDeviceId 来源设备标识
     * @param tagId 标签标识
     * @param vehicleId 发生时车辆标识
     * @param personId 发生时人员标识
     * @param coordinateSystemId 统一坐标系标识
     * @param ingestSequence 摄取序号
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
    public record Point(
            UUID eventId,
            UUID organizationId,
            UUID siteId,
            UUID sourceDeviceId,
            UUID tagId,
            UUID vehicleId,
            UUID personId,
            UUID coordinateSystemId,
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
    }
}
