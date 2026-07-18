package com.bosco.mine.messaging.location;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 通知定位运行服务拉取指定不可变快照的轻量载荷。
 *
 * @param snapshotId 不可变定位运行快照标识
 * @param revision 矿区内单调递增的运行修订号
 * @param contentHash 完整快照的规范化 SHA-256 摘要
 */
public record LocationRuntimeChangedPayload(
        UUID snapshotId,
        long revision,
        String contentHash
) {

    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** 校验快照定位、修订号和完整性摘要。 */
    public LocationRuntimeChangedPayload {
        Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须大于 0");
        }
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash 必须是规范化 SHA-256");
        }
    }
}
