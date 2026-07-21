package com.bosco.mine.server.modules.mine.spatial.dto;

import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * 可审计的单条空间事件及其有界判定证据。
 *
 * @param eventId 空间事件标识
 * @param eventSequence 空间事件持久化序号
 * @param organizationId 组织标识
 * @param siteId 矿区标识
 * @param zoneType 区域类型
 * @param zoneId 稳定区域标识
 * @param zoneVersionId 发布区域版本标识
 * @param tagId 标签标识
 * @param vehicleId 车辆标识
 * @param personId 人员标识
 * @param eventType 事件类型
 * @param anchorLocationEventId 锚点位置事实标识
 * @param previousState 事件前状态
 * @param currentState 事件后状态
 * @param confirmationPoints 连续确认点数
 * @param ingestSequence 锚点位置摄取序号
 * @param runtimeRevision 判定运行修订号
 * @param occurredAt 业务发生时间
 * @param createdAt 持久化时间
 * @param evidence 有界判定证据
 */
public record SpatialEventDetailResponse(
        UUID eventId,
        long eventSequence,
        UUID organizationId,
        UUID siteId,
        String zoneType,
        UUID zoneId,
        UUID zoneVersionId,
        UUID tagId,
        UUID vehicleId,
        UUID personId,
        String eventType,
        UUID anchorLocationEventId,
        String previousState,
        String currentState,
        int confirmationPoints,
        long ingestSequence,
        long runtimeRevision,
        Instant occurredAt,
        Instant createdAt,
        RealtimeSpatialEvent.Evidence evidence
) {
}
