package com.bosco.mine.server.modules.mine.location;

import com.bosco.mine.messaging.location.LocationRuntimeChangedPayload;

import java.util.Objects;
import java.util.UUID;

/**
 * 定位运行快照事务中待写入 Outbox 的轻量事件。
 *
 * @param eventId Outbox 事件标识
 * @param snapshotId 不可变快照标识
 * @param siteId 用于顺序分区的矿区标识
 * @param revision 矿区内单调修订号
 * @param contentHash 完整快照摘要
 * @param traceId 跨服务跟踪标识
 */
public record LocationSnapshotOutboxEvent(
        UUID eventId,
        UUID snapshotId,
        UUID siteId,
        long revision,
        String contentHash,
        String traceId
) {

    /** 校验可靠事件必需字段。 */
    public LocationSnapshotOutboxEvent {
        Objects.requireNonNull(eventId, "Outbox 事件标识不能为空");
        Objects.requireNonNull(snapshotId, "快照标识不能为空");
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        Objects.requireNonNull(traceId, "跟踪标识不能为空");
        new LocationRuntimeChangedPayload(snapshotId, revision, contentHash);
    }

    /** 构造严格仅含快照、修订和摘要的公开载荷。 */
    public LocationRuntimeChangedPayload payload() {
        return new LocationRuntimeChangedPayload(snapshotId, revision, contentHash);
    }
}
