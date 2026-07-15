package com.bosco.mine.gateway.normalize;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 MQTT JSON 报文到统一设备消息的身份绑定和输入限制。
 */
class DeviceMessageNormalizerTest {

    private static final String DEVICE_ID = "019f63e2-071f-706a-bd12-4dad7150e75d";
    private static final String ROUTING_KEY =
            "mine.v1.haul-truck.device-credential-001.up.telemetry";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-15T02:00:00Z"), ZoneOffset.UTC
    );

    private DeviceMessageNormalizer normalizer;

    @BeforeEach
    void setUp() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck",
                "device-credential-001",
                DEVICE_ID,
                "simulator-001",
                "mine-site-001",
                "mqtt-json-v1"
        )));
        normalizer = new DeviceMessageNormalizer(
                registry,
                IdGenerator.timeOrdered(),
                CLOCK,
                2048
        );
    }

    @Test
    void 应使用可信凭据映射生成UUIDv7统一消息() {
        MessageEnvelope<UnifiedDeviceMessage> envelope = normalizer.normalize(
                ROUTING_KEY,
                validPayload("simulator-001")
        );

        assertThat(envelope.eventId().version()).isEqualTo(7);
        assertThat(envelope.eventId()).isEqualTo(envelope.payload().messageId());
        assertThat(envelope.eventType().value()).isEqualTo("telemetry.normalized");
        assertThat(envelope.partitionKey()).isEqualTo(DEVICE_ID);
        assertThat(envelope.tenantScope()).isEqualTo("mine-site-001");
        assertThat(envelope.publishedAt()).isEqualTo(Instant.parse("2026-07-15T02:00:00Z"));
        assertThat(envelope.payload().deviceId()).isEqualTo(DEVICE_ID);
        assertThat(envelope.payload().productKey()).isEqualTo("haul-truck");
        assertThat(envelope.payload().receivedAt()).isEqualTo(Instant.parse("2026-07-15T02:00:00Z"));
        assertThat(envelope.payload().occurredAt()).isEqualTo(Instant.parse("2026-07-15T01:59:58Z"));
        assertThat(envelope.payload().properties().values())
                .containsEntry("engine.rpm", 1280)
                .containsEntry("vehicle.speed", 12.5);
    }

    @Test
    void 业务设备编码与可信映射不一致时应拒绝() {
        assertThatThrownBy(() -> normalizer.normalize(
                ROUTING_KEY,
                validPayload("another-device")
        ))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("deviceCode");
    }

    @Test
    void 未登记的MQTT客户端身份应拒绝() {
        assertThatThrownBy(() -> normalizer.normalize(
                "mine.v1.haul-truck.unknown-client.up.telemetry",
                validPayload("simulator-001")
        ))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("未登记");
    }

    @Test
    void 超过配置上限的原始报文应在解析前拒绝() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        DeviceMessageNormalizer limited = new DeviceMessageNormalizer(
                registry,
                IdGenerator.timeOrdered(),
                CLOCK,
                32
        );

        assertThatThrownBy(() -> limited.normalize(ROUTING_KEY, validPayload("simulator-001")))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("超过");
    }

    @Test
    void 主题身份包含空段时应作为不可重试报文拒绝() {
        assertThatThrownBy(() -> normalizer.normalize(
                "mine.v1..device-credential-001.up.telemetry",
                validPayload("simulator-001")
        ))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("routing key");
    }

    private byte[] validPayload(String deviceCode) {
        return ("""
                {
                  "productKey":"haul-truck",
                  "deviceCode":"%s",
                  "bootId":"simulator-boot-001",
                  "sequence":7,
                  "occurredAt":"2026-07-15T01:59:58Z",
                  "replayed":false,
                  "properties":{"engine.rpm":1280,"vehicle.speed":12.5}
                }
                """.formatted(deviceCode)).getBytes(StandardCharsets.UTF_8);
    }
}
