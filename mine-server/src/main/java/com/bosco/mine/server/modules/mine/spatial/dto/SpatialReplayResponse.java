package com.bosco.mine.server.modules.mine.spatial.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 空间历史重放任务登记结果。
 *
 * @param requestId 重放请求标识
 * @param siteId 矿区标识
 * @param zoneType 区域类型
 * @param zoneVersionId 发布区域版本标识
 * @param from 重放开始时间
 * @param to 重放结束时间
 * @param status 异步任务状态
 * @param createdAt 登记时间
 */
public record SpatialReplayResponse(
        UUID requestId,
        UUID siteId,
        String zoneType,
        UUID zoneVersionId,
        Instant from,
        Instant to,
        String status,
        Instant createdAt
) {
}
