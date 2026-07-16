package com.bosco.mine.messaging.configuration;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 通知运行节点拉取指定不可变配置快照的轻量发布事件。
 *
 * @param releaseId 配置发布标识
 * @param packageId 不可变配置包标识
 * @param productId 设备产品标识
 * @param releaseSequence 产品内单调递增发布序号
 * @param contentHash 完整快照的规范化 SHA-256
 */
public record ConfigReleasePayload(
        UUID releaseId,
        UUID packageId,
        UUID productId,
        long releaseSequence,
        String contentHash
) {

    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** 校验配置发布事件的定位字段和单调序号。 */
    public ConfigReleasePayload {
        Objects.requireNonNull(releaseId, "releaseId 不能为空");
        Objects.requireNonNull(packageId, "packageId 不能为空");
        Objects.requireNonNull(productId, "productId 不能为空");
        if (releaseSequence < 1) {
            throw new IllegalArgumentException("releaseSequence 必须大于 0");
        }
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash 必须是规范化 SHA-256");
        }
    }
}
