package com.bosco.mine.server.modules.mine.spatial.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 空间事件固定水位分页响应。
 *
 * @param siteId 矿区标识
 * @param from 查询开始时间，包含
 * @param to 查询结束时间，包含
 * @param watermark 首页固定的事件持久化水位
 * @param nextCursor 下一页不透明游标
 * @param items 当前页事件摘要
 */
public record SpatialEventPageResponse(
        UUID siteId,
        Instant from,
        Instant to,
        long watermark,
        String nextCursor,
        List<Item> items
) {

    /** 创建不可变分页响应。 */
    public SpatialEventPageResponse {
        items = List.copyOf(items);
    }

    /**
     * 空间事件列表摘要。
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
     * @param previousState 事件前状态
     * @param currentState 事件后状态
     * @param confirmationPoints 连续确认点数
     * @param ingestSequence 锚点位置摄取序号
     * @param runtimeRevision 判定运行修订号
     * @param occurredAt 业务发生时间
     */
    public record Item(
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
            String previousState,
            String currentState,
            int confirmationPoints,
            long ingestSequence,
            long runtimeRevision,
            Instant occurredAt
    ) {
    }
}
