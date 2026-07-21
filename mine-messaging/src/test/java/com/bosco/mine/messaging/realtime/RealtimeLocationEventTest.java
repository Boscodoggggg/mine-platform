package com.bosco.mine.messaging.realtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验已持久化定位事实的实时事件契约。
 */
class RealtimeLocationEventTest {

    private static final UUID EVENT_ID =
            UUID.fromString("019f6d10-0000-7000-8000-000000000101");
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID SITE_ID = UUID.randomUUID();
    private static final UUID SOURCE_DEVICE_ID = UUID.randomUUID();
    private static final UUID TAG_ID = UUID.randomUUID();
    private static final UUID COORDINATE_SYSTEM_ID = UUID.randomUUID();

    @Test
    void 应表达已固化作用域与统一坐标的实时位置() {
        RealtimeLocationEvent event = event(
                9L, 10D, 20D, 2D, 6.5D, 180D, 0.4D,
                RealtimeLocationEvent.QualityStatus.GOOD, null);

        assertThat(event.siteId()).isEqualTo(SITE_ID);
        assertThat(event.tagCode()).isEqualTo("tag-001");
        assertThat(event.sourceSequence()).isEqualTo(9L);
        assertThat(event.qualityStatus()).isEqualTo(RealtimeLocationEvent.QualityStatus.GOOD);
    }

    @Test
    void 应规范化降级质量原因() {
        RealtimeLocationEvent event = event(
                9L, 10D, 20D, null, null, null, null,
                RealtimeLocationEvent.QualityStatus.DEGRADED, "  LOW_ACCURACY  ");

        assertThat(event.qualityReason()).isEqualTo("LOW_ACCURACY");
    }

    @Test
    void 应拒绝非正摄取序号非有限统一坐标和不一致质量原因() {
        assertThatThrownBy(() -> event(
                0L, 10D, 20D, null, null, null, null,
                RealtimeLocationEvent.QualityStatus.GOOD, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("摄取序号");
        assertThatThrownBy(() -> event(
                1L, Double.NaN, 20D, null, null, null, null,
                RealtimeLocationEvent.QualityStatus.GOOD, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("坐标");
        assertThatThrownBy(() -> event(
                1L, 10D, 20D, null, null, null, null,
                RealtimeLocationEvent.QualityStatus.GOOD, "LOW_ACCURACY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("质量原因");
        assertThatThrownBy(() -> event(
                1L, 10D, 20D, null, null, null, null,
                RealtimeLocationEvent.QualityStatus.DEGRADED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("质量原因");
    }

    private RealtimeLocationEvent event(
            long sourceSequence,
            double localX,
            double localY,
            Double localZ,
            Double speed,
            Double heading,
            Double accuracy,
            RealtimeLocationEvent.QualityStatus qualityStatus,
            String qualityReason
    ) {
        return new RealtimeLocationEvent(
                EVENT_ID, ORGANIZATION_ID, SITE_ID, SOURCE_DEVICE_ID,
                TAG_ID, "tag-001", COORDINATE_SYSTEM_ID,
                null, null, sourceSequence,
                Instant.parse("2026-07-18T08:00:00Z"),
                Instant.parse("2026-07-18T08:00:02Z"),
                localX, localY, localZ, speed, heading, accuracy,
                qualityStatus, qualityReason);
    }
}
