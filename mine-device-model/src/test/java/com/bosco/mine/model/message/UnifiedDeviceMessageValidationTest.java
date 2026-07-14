package com.bosco.mine.model.message;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验统一设备消息的必填语义和遥测属性隔离。
 */
class UnifiedDeviceMessageValidationTest {

    @Test
    void 应拒绝空白设备标识() {
        assertThatThrownBy(() -> message(" ", new TelemetryPayload(Map.of("engine.rpm", 1350))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviceId");
    }

    @Test
    void 应防止调用方修改遥测属性() {
        Map<String, Object> values = new HashMap<>();
        values.put("engine.rpm", 1350);
        TelemetryPayload payload = new TelemetryPayload(values);
        UnifiedDeviceMessage message = message("device-001", payload);

        values.put("engine.rpm", 1600);

        assertThat(message.properties().values()).containsEntry("engine.rpm", 1350);
        assertThatThrownBy(() -> message.properties().values().put("engine.rpm", 1700))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 应拒绝非UUIDv7消息标识() {
        assertThatThrownBy(() -> message(UUID.randomUUID(), "device-001",
                new TelemetryPayload(Map.of("engine.rpm", 1350))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv7");
    }

    private UnifiedDeviceMessage message(String deviceId, TelemetryPayload properties) {
        return message(UUID.fromString("0190b4e8-3c01-7abc-8def-1234567890ab"), deviceId, properties);
    }

    private UnifiedDeviceMessage message(UUID messageId, String deviceId, TelemetryPayload properties) {
        return new UnifiedDeviceMessage(
                messageId,
                UnifiedDeviceMessage.MessageKind.TELEMETRY,
                "haul-truck",
                deviceId,
                "truck-vendor-a@3",
                "boot-20260714-01",
                12345L,
                Instant.parse("2026-07-14T08:00:00Z"),
                Instant.parse("2026-07-14T08:00:02Z"),
                false,
                properties,
                UnifiedDeviceMessage.Quality.GOOD,
                "s3://raw/device-001/message.bin");
    }
}
