package com.bosco.mine.telemetry.location.snapshot;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 从受信任控制面按稳定条目键游标拉取不可变定位运行快照。 */
@FunctionalInterface
public interface LocationSnapshotSource {

    /**
     * 拉取一页定位运行快照。
     *
     * @param snapshotId 快照标识
     * @param afterItemKey 上一页最后条目键，首页为空
     * @param limit 单页最大条目数
     * @return 不可变快照页
     */
    SnapshotPage fetch(UUID snapshotId, String afterItemKey, int limit);

    /**
     * 控制面不可变定位运行快照页。
     *
     * @param snapshotId 快照标识
     * @param organizationId 客户组织标识
     * @param siteId 矿区标识
     * @param revision 矿区单调修订号
     * @param itemCount 完整快照条目数
     * @param contentHash 完整快照摘要
     * @param createdAt 控制面创建时间
     * @param items 当前页条目
     * @param nextAfterItemKey 下一页游标
     */
    record SnapshotPage(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            int itemCount,
            String contentHash,
            Instant createdAt,
            List<SnapshotItem> items,
            String nextAfterItemKey
    ) {

        /** 冻结当前页条目。 */
        public SnapshotPage {
            Objects.requireNonNull(items, "定位快照页条目不能为空");
            items = List.copyOf(items);
        }
    }

    /**
     * 定位运行快照单条非敏感内容。
     *
     * @param itemKey 稳定类型与资源联合键
     * @param itemType 固定条目类型
     * @param content 结构化非敏感内容
     * @param contentHash 单条规范化内容摘要
     */
    record SnapshotItem(
            String itemKey,
            String itemType,
            JsonNode content,
            String contentHash
    ) {

        /** 冻结可变 JSON 树，避免校验后被调用方修改。 */
        public SnapshotItem {
            Objects.requireNonNull(content, "定位快照条目内容不能为空");
            content = content.deepCopy();
        }

        /** @return 与内部状态隔离的 JSON 内容副本 */
        @Override
        public JsonNode content() {
            return content.deepCopy();
        }
    }
}
