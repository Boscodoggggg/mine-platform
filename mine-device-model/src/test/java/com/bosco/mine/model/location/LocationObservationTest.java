package com.bosco.mine.model.location;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验定位观测稳定契约的身份、编码、数值和时序边界。
 */
class LocationObservationTest {

    private static final UUID OBSERVATION_ID =
            UUID.fromString("019f6d10-0000-7000-8000-000000000001");
    private static final UUID SOURCE_DEVICE_ID =
            UUID.fromString("019f6d10-0000-7000-8000-000000000002");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-18T08:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-18T08:00:02Z");

    @Test
    void 应接受带可选三维与运动数据的可追溯观测() {
        LocationObservation observation = observation(
                OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                128.5D, 64.25D, 12.0D,
                8.5D, 359.9D, 0.3D,
                "boot-20260718-01", 42L, OCCURRED_AT, RECEIVED_AT);

        assertThat(observation.observationId()).isEqualTo(OBSERVATION_ID);
        assertThat(observation.sourceDeviceId()).isEqualTo(SOURCE_DEVICE_ID);
        assertThat(observation.tagCode()).isEqualTo("tag-001");
        assertThat(observation.coordinateSystemCode()).isEqualTo("vendor-local");
        assertThat(observation.z()).isEqualTo(12.0D);
        assertThat(observation.sequence()).isEqualTo(42L);
    }

    @Test
    void 应允许二维设备省略高程和运动精度() {
        LocationObservation observation = observation(
                OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                128.5D, 64.25D, null,
                null, null, null,
                "boot-20260718-01", 1L, OCCURRED_AT, RECEIVED_AT);

        assertThat(observation.z()).isNull();
        assertThat(observation.speedMetersPerSecond()).isNull();
        assertThat(observation.headingDegrees()).isNull();
        assertThat(observation.accuracyMeters()).isNull();
    }

    @Test
    void 应拒绝非UUIDv7观测和缺失的可信来源设备() {
        assertThatThrownBy(() -> observation(
                UUID.randomUUID(), SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                1D, 2D, null, null, null, null,
                "boot-a", 1L, OCCURRED_AT, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7");
        assertThatThrownBy(() -> observation(
                OBSERVATION_ID, null, "tag-001", "vendor-local",
                1D, 2D, null, null, null, null,
                "boot-a", 1L, OCCURRED_AT, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("来源设备");
    }

    @Test
    void 应拒绝无法与矿区主数据匹配的标签和坐标系编码() {
        for (String invalid : new String[]{null, "", "Tag-001", "tag_001", "a", "a".repeat(65)}) {
            assertThatThrownBy(() -> observation(
                    OBSERVATION_ID, SOURCE_DEVICE_ID, invalid, "vendor-local",
                    1D, 2D, null, null, null, null,
                    "boot-a", 1L, OCCURRED_AT, RECEIVED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("标签编码");
            assertThatThrownBy(() -> observation(
                    OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", invalid,
                    1D, 2D, null, null, null, null,
                    "boot-a", 1L, OCCURRED_AT, RECEIVED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("坐标系编码");
        }
    }

    @Test
    void 应拒绝非有限坐标与非法速度航向和精度() {
        assertInvalidNumbers(Double.NaN, 2D, null, null, null, null, "坐标");
        assertInvalidNumbers(1D, Double.POSITIVE_INFINITY, null, null, null, null, "坐标");
        assertInvalidNumbers(1D, 2D, Double.NEGATIVE_INFINITY, null, null, null, "坐标");
        assertInvalidNumbers(1D, 2D, null, -0.1D, null, null, "速度");
        assertInvalidNumbers(1D, 2D, null, Double.NaN, null, null, "速度");
        assertInvalidNumbers(1D, 2D, null, null, -0.1D, null, "航向");
        assertInvalidNumbers(1D, 2D, null, null, 360D, null, "航向");
        assertInvalidNumbers(1D, 2D, null, null, null, 0D, "精度");
        assertInvalidNumbers(1D, 2D, null, null, null, Double.NaN, "精度");
    }

    @Test
    void 应拒绝空启动标识非正序号和缺失时间() {
        assertThatThrownBy(() -> observation(
                OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                1D, 2D, null, null, null, null,
                " ", 1L, OCCURRED_AT, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("启动标识");
        assertThatThrownBy(() -> observation(
                OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                1D, 2D, null, null, null, null,
                "a".repeat(129), 1L, OCCURRED_AT, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("启动标识");
        assertThatThrownBy(() -> observation(
                OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                1D, 2D, null, null, null, null,
                "boot-a", 0L, OCCURRED_AT, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
        assertThatThrownBy(() -> observation(
                OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                1D, 2D, null, null, null, null,
                "boot-a", 1L, null, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("发生时间");
        assertThatThrownBy(() -> observation(
                OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                1D, 2D, null, null, null, null,
                "boot-a", 1L, OCCURRED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("接收时间");
    }

    private void assertInvalidNumbers(
            double x,
            double y,
            Double z,
            Double speed,
            Double heading,
            Double accuracy,
            String message
    ) {
        assertThatThrownBy(() -> observation(
                OBSERVATION_ID, SOURCE_DEVICE_ID, "tag-001", "vendor-local",
                x, y, z, speed, heading, accuracy,
                "boot-a", 1L, OCCURRED_AT, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private LocationObservation observation(
            UUID observationId,
            UUID sourceDeviceId,
            String tagCode,
            String coordinateSystemCode,
            double x,
            double y,
            Double z,
            Double speed,
            Double heading,
            Double accuracy,
            String bootId,
            long sequence,
            Instant occurredAt,
            Instant receivedAt
    ) {
        return new LocationObservation(
                observationId, sourceDeviceId, tagCode, coordinateSystemCode,
                x, y, z, speed, heading, accuracy,
                bootId, sequence, occurredAt, receivedAt);
    }
}
