package com.bosco.mine.gateway.normalize;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.configuration.ProtocolSnapshotRegistry;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.protocol.engine.binding.ProductProtocolBinding;
import com.bosco.mine.protocol.engine.json.FieldTransform;
import com.bosco.mine.protocol.engine.json.MqttJsonExecutionPlan;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolCompiler;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolDefinition;
import com.bosco.mine.protocol.engine.mqtt.MqttBinaryProtocolPluginRegistry;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodeContext;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodedMessage;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void 已发布动态协议应解析厂商差异报文并输出统一属性() {
        DeviceSessionRegistry identities = registry();
        ProtocolSnapshotRegistry protocols = mock(ProtocolSnapshotRegistry.class);
        MqttJsonExecutionPlan plan = new MqttJsonProtocolCompiler().compile(
                new MqttJsonProtocolDefinition(
                        "vendor-truck-json",
                        3,
                        new MqttJsonProtocolDefinition.MetadataPaths(
                                "meta.product", "meta.device", "meta.boot", "meta.seq",
                                "meta.timestamp", "meta.replay"),
                        List.of(new MqttJsonProtocolDefinition.PropertyDefinition(
                                "engineRpm", MqttJsonProtocolDefinition.ValueType.INTEGER)),
                        List.of(new MqttJsonProtocolDefinition.FieldMapping(
                                "data.rpm", MqttJsonProtocolDefinition.ValueType.INTEGER, "engineRpm",
                                FieldTransform.NONE, null, null, Map.of(), true))
                ));
        when(protocols.find("haul-truck")).thenReturn(Optional.of(
                new ProtocolSnapshotRegistry.ActiveProtocol(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 8,
                        "sha256:" + "a".repeat(64), "haul-truck", plan)
        ));
        DeviceMessageNormalizer dynamic = new DeviceMessageNormalizer(
                identities, protocols, IdGenerator.timeOrdered(), CLOCK, 2048);

        MessageEnvelope<UnifiedDeviceMessage> envelope = dynamic.normalize(
                ROUTING_KEY,
                """
                        {
                          "meta":{"product":"haul-truck","device":"simulator-001",
                                  "boot":"boot-vendor","seq":11,
                                  "timestamp":"2026-07-15T01:59:58Z","replay":false},
                          "data":{"rpm":1550}
                        }
                        """.getBytes(StandardCharsets.UTF_8)
        );

        assertThat(envelope.payload().protocolVersion()).isEqualTo("vendor-truck-json:v3");
        assertThat(envelope.payload().bootId()).isEqualTo("boot-vendor");
        assertThat(envelope.payload().properties().values()).containsEntry("engineRpm", 1550);
    }

    @Test
    void 强制动态配置模式下未加载产品快照应拒绝报文() {
        DeviceMessageNormalizer required = new DeviceMessageNormalizer(
                registry(), new ProtocolSnapshotRegistry(true),
                IdGenerator.timeOrdered(), CLOCK, 2048);

        assertThatThrownBy(() -> required.normalize(ROUTING_KEY, validPayload("simulator-001")))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("尚未加载");
    }

    @Test
    void MQTT二进制产品应使用绑定插件且平台身份不能被覆盖() {
        DeviceSessionRegistry identities = registry();
        ProtocolSnapshotRegistry protocols = mock(ProtocolSnapshotRegistry.class);
        ProductProtocolBinding binding = new ProductProtocolBinding(
                ProductProtocolBinding.Transport.MQTT,
                ProductProtocolBinding.PayloadFormat.BINARY,
                ProductProtocolBinding.RoutingMode.TRUSTED_IDENTITY,
                ProductProtocolBinding.ParserType.JAVA_PLUGIN,
                "test-mqtt-binary",
                "test-mqtt-binary:v1"
        );
        when(protocols.find("haul-truck")).thenReturn(Optional.of(
                new ProtocolSnapshotRegistry.ActiveProtocol(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 9,
                        "sha256:" + "b".repeat(64), "haul-truck", binding, null)
        ));
        MqttBinaryProtocolPlugin plugin = new MqttBinaryProtocolPlugin() {
            @Override
            public String protocolKey() {
                return "test-mqtt-binary";
            }

            @Override
            public String protocolVersion() {
                return "test-mqtt-binary:v1";
            }

            @Override
            public MqttBinaryDecodedMessage decode(
                    MqttBinaryDecodeContext context,
                    byte[] payload
            ) {
                assertThat(context.productKey()).isEqualTo("haul-truck");
                assertThat(context.clientId()).isEqualTo("device-credential-001");
                assertThat(context.deviceCode()).isEqualTo("simulator-001");
                assertThat(payload).containsExactly(0x01, 0x02, 0x03);
                return new MqttBinaryDecodedMessage(
                        "binary-boot", 12L, Instant.parse("2026-07-15T01:59:57Z"),
                        false, Map.of("engineRpm", 1660)
                );
            }
        };
        DeviceMessageNormalizer binary = new DeviceMessageNormalizer(
                identities,
                protocols,
                new MqttBinaryProtocolPluginRegistry(List.of(plugin)),
                IdGenerator.timeOrdered(),
                CLOCK,
                2048
        );

        MessageEnvelope<UnifiedDeviceMessage> envelope = binary.normalize(
                ROUTING_KEY, new byte[]{0x01, 0x02, 0x03});

        assertThat(envelope.payload().productKey()).isEqualTo("haul-truck");
        assertThat(envelope.payload().deviceId()).isEqualTo(DEVICE_ID);
        assertThat(envelope.payload().protocolVersion()).isEqualTo("test-mqtt-binary:v1");
        assertThat(envelope.payload().properties().values()).containsEntry("engineRpm", 1660);
    }

    @Test
    void MQTT二进制绑定版本与安装插件不一致时应拒绝() {
        ProtocolSnapshotRegistry protocols = mock(ProtocolSnapshotRegistry.class);
        ProductProtocolBinding binding = new ProductProtocolBinding(
                ProductProtocolBinding.Transport.MQTT,
                ProductProtocolBinding.PayloadFormat.BINARY,
                ProductProtocolBinding.RoutingMode.TRUSTED_IDENTITY,
                ProductProtocolBinding.ParserType.JAVA_PLUGIN,
                "test-mqtt-binary",
                "test-mqtt-binary:v2"
        );
        when(protocols.find("haul-truck")).thenReturn(Optional.of(
                new ProtocolSnapshotRegistry.ActiveProtocol(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 9,
                        "sha256:" + "b".repeat(64), "haul-truck", binding, null)
        ));
        MqttBinaryProtocolPlugin plugin = mock(MqttBinaryProtocolPlugin.class);
        when(plugin.protocolKey()).thenReturn("test-mqtt-binary");
        when(plugin.protocolVersion()).thenReturn("test-mqtt-binary:v1");
        when(plugin.apiMajorVersion()).thenReturn(MqttBinaryProtocolPlugin.API_MAJOR_VERSION);
        DeviceMessageNormalizer binary = new DeviceMessageNormalizer(
                registry(), protocols, new MqttBinaryProtocolPluginRegistry(List.of(plugin)),
                IdGenerator.timeOrdered(), CLOCK, 2048);

        assertThatThrownBy(() -> binary.normalize(ROUTING_KEY, new byte[]{0x01}))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("版本");
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

    private DeviceSessionRegistry registry() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck",
                "device-credential-001",
                DEVICE_ID,
                "simulator-001",
                "mine-site-001",
                "mqtt-json-v1"
        )));
        return registry;
    }
}
