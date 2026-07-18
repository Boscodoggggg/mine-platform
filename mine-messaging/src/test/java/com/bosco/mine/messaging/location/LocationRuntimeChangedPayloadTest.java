package com.bosco.mine.messaging.location;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验定位运行快照轻量事件的定位与完整性边界。
 */
class LocationRuntimeChangedPayloadTest {

    private static final UUID SNAPSHOT_ID =
            UUID.fromString("019f6d00-0000-7000-8000-000000000101");

    @Test
    void 应接受可定位和校验的轻量定位运行事件() {
        LocationRuntimeChangedPayload payload = new LocationRuntimeChangedPayload(
                SNAPSHOT_ID, 7, "sha256:" + "a".repeat(64));

        assertThat(payload.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(payload.revision()).isEqualTo(7);
        assertThat(payload.contentHash()).isEqualTo("sha256:" + "a".repeat(64));
    }

    @Test
    void 应拒绝空快照非正修订和非规范哈希() {
        assertThatThrownBy(() -> new LocationRuntimeChangedPayload(
                null, 1, "sha256:" + "a".repeat(64)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LocationRuntimeChangedPayload(
                SNAPSHOT_ID, 0, "sha256:" + "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocationRuntimeChangedPayload(
                SNAPSHOT_ID, 1, "SHA256:" + "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocationRuntimeChangedPayload(
                SNAPSHOT_ID, 1, "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
