package com.bosco.mine.gateway.configuration;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理服务返回给 Gateway 的不可变配置发布快照。
 *
 * @param releaseId 配置发布标识
 * @param productId 产品标识
 * @param packageId 不可变配置包标识
 * @param releaseSequence 产品内单调发布序号
 * @param contentHash 快照规范化 SHA-256
 * @param snapshotJson 完整非敏感快照 JSON
 */
public record ConfigurationSnapshot(
        UUID releaseId,
        UUID productId,
        UUID packageId,
        long releaseSequence,
        String contentHash,
        String snapshotJson
) {

    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** 校验发布定位字段、单调序号和哈希格式。 */
    public ConfigurationSnapshot {
        Objects.requireNonNull(releaseId, "releaseId 不能为空");
        Objects.requireNonNull(productId, "productId 不能为空");
        Objects.requireNonNull(packageId, "packageId 不能为空");
        if (releaseSequence < 1) {
            throw new IllegalArgumentException("releaseSequence 必须大于 0");
        }
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash 必须是规范化 SHA-256");
        }
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("snapshotJson 不能为空");
        }
    }
}
