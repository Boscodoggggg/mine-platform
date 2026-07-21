package com.bosco.mine.messaging.strategy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验策略运行快照轻量事件的定位和版本边界。
 */
class StrategyRuntimeChangedPayloadTest {

    private static final UUID SNAPSHOT_ID =
            UUID.fromString("019f6d00-0000-7000-8000-000000000001");
    private static final UUID PRODUCT_ID =
            UUID.fromString("019f6d00-0000-7000-8000-000000000002");

    @Test
    void 应接受可定位和校验的轻量策略运行事件() {
        StrategyRuntimeChangedPayload payload = new StrategyRuntimeChangedPayload(
                SNAPSHOT_ID, PRODUCT_ID, "DATA_QUALITY", 7, 120,
                "sha256:" + "a".repeat(64)
        );

        assertThat(payload.runtimeRevision()).isEqualTo(7);
        assertThat(payload.itemCount()).isEqualTo(120);
    }

    @Test
    void 应拒绝非法类型修订条目数和哈希() {
        assertThatThrownBy(() -> new StrategyRuntimeChangedPayload(
                SNAPSHOT_ID, PRODUCT_ID, "data-quality", 1, 0,
                "sha256:" + "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StrategyRuntimeChangedPayload(
                SNAPSHOT_ID, PRODUCT_ID, "DATA_QUALITY", 0, 0,
                "sha256:" + "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StrategyRuntimeChangedPayload(
                SNAPSHOT_ID, PRODUCT_ID, "DATA_QUALITY", 1, -1,
                "sha256:" + "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StrategyRuntimeChangedPayload(
                SNAPSHOT_ID, PRODUCT_ID, "DATA_QUALITY", 1, 0, "invalid"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
