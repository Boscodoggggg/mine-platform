package com.bosco.mine.gateway.tcp;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.protocol.sdk.tcp.TcpDecodedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 TCP 插件中间结果只能经平台可信身份映射进入统一消息。
 */
class TcpMessageNormalizerTest {

    private static final String DEVICE_ID = "019f63e2-071f-706a-bd12-4dad7150e75d";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T02:00:00Z"), ZoneOffset.UTC
    );

    private DeviceSessionRegistry registry;
    private TcpMessageNormalizer normalizer;

    @BeforeEach
    void setUp() {
        registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck", "tcp-credential-001", DEVICE_ID, "truck-001",
                "mine-site-001", "haul-truck-binary:v1"
        )));
        normalizer = new TcpMessageNormalizer(registry, IdGenerator.timeOrdered(), CLOCK);
    }

    @Test
    void 应生成使用平台设备标识和插件版本的UUIDv7统一消息() {
        TcpDecodedMessage decoded = decoded("haul-truck", "truck-001");

        MessageEnvelope<UnifiedDeviceMessage> envelope = normalizer.normalize(
                decoded, "haul-truck-binary:v1"
        );

        assertThat(envelope.eventId().version()).isEqualTo(7);
        assertThat(envelope.eventId()).isEqualTo(envelope.payload().messageId());
        assertThat(envelope.eventType().value()).isEqualTo("telemetry.normalized");
        assertThat(envelope.partitionKey()).isEqualTo(DEVICE_ID);
        assertThat(envelope.tenantScope()).isEqualTo("mine-site-001");
        assertThat(envelope.correlationId()).isNull();
        assertThat(envelope.payload().deviceId()).isEqualTo(DEVICE_ID);
        assertThat(envelope.payload().productKey()).isEqualTo("haul-truck");
        assertThat(envelope.payload().protocolVersion()).isEqualTo("haul-truck-binary:v1");
        assertThat(envelope.payload().receivedAt()).isEqualTo(Instant.parse("2026-07-17T02:00:00Z"));
        assertThat(envelope.payload().occurredAt()).isEqualTo(Instant.parse("2026-07-17T01:59:58Z"));
        assertThat(envelope.payload().properties().values())
                .containsEntry("engine.rpm", 1350)
                .containsEntry("vehicle.speed", new BigDecimal("12.5"));
    }

    @Test
    void 未登记帧内身份应拒绝() {
        assertThatThrownBy(() -> normalizer.normalize(
                decoded("haul-truck", "unknown"), "haul-truck-binary:v1"))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("未登记");
    }

    @Test
    void 身份配置协议版本与监听插件不一致时应拒绝() {
        assertThatThrownBy(() -> normalizer.normalize(
                decoded("haul-truck", "truck-001"), "other-binary:v2"))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("协议版本");
    }

    private TcpDecodedMessage decoded(String productKey, String deviceCode) {
        return new TcpDecodedMessage(
                productKey, deviceCode, "boot-20260717", 42L,
                Instant.parse("2026-07-17T01:59:58Z"), false,
                Map.of("engine.rpm", 1350, "vehicle.speed", new BigDecimal("12.5"))
        );
    }
}
