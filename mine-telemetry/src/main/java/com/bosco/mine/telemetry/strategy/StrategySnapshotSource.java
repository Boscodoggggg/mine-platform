package com.bosco.mine.telemetry.strategy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 从受信任控制面按设备游标拉取不可变策略运行快照。
 */
public interface StrategySnapshotSource {

    /**
     * 拉取一页策略运行快照。
     *
     * @param snapshotId 快照标识
     * @param afterDeviceId 上一页最后设备标识，首页为空
     * @param limit 单页最大条目数
     * @return 不可变快照页
     */
    SnapshotPage fetch(UUID snapshotId, UUID afterDeviceId, int limit);

    /** 控制面不可变策略运行快照页。 */
    record SnapshotPage(
            UUID snapshotId,
            UUID productId,
            String strategyType,
            long runtimeRevision,
            int itemCount,
            String contentHash,
            List<SnapshotItem> items,
            UUID nextAfterDeviceId
    ) {
        /** 冻结快照页条目。 */
        public SnapshotPage {
            items = List.copyOf(items);
        }
    }

    /** 控制面单设备最终有效策略或删除条目。 */
    record SnapshotItem(
            UUID deviceId,
            Map<String, Object> values,
            String contentHash
    ) {
        /** 冻结策略字段。 */
        public SnapshotItem {
            values = Map.copyOf(values);
        }
    }
}
