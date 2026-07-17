package com.bosco.mine.messaging.strategy;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 通知运行服务拉取指定不可变策略快照的轻量事件。
 *
 * @param snapshotId 不可变策略快照标识
 * @param productId 设备产品标识
 * @param strategyType 策略类型
 * @param runtimeRevision 产品和策略类型内单调递增的运行修订号
 * @param itemCount 快照包含的设备策略条目数
 * @param contentHash 完整快照的规范化 SHA-256
 */
public record StrategyRuntimeChangedPayload(
        UUID snapshotId,
        UUID productId,
        String strategyType,
        long runtimeRevision,
        int itemCount,
        String contentHash
) {

    private static final Pattern STRATEGY_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** 校验策略运行事件的定位字段、修订号和完整性摘要。 */
    public StrategyRuntimeChangedPayload {
        Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        Objects.requireNonNull(productId, "productId 不能为空");
        if (strategyType == null || !STRATEGY_TYPE.matcher(strategyType).matches()) {
            throw new IllegalArgumentException("strategyType 必须是规范化大写策略类型");
        }
        if (runtimeRevision < 1) {
            throw new IllegalArgumentException("runtimeRevision 必须大于 0");
        }
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount 不能小于 0");
        }
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash 必须是规范化 SHA-256");
        }
    }
}
