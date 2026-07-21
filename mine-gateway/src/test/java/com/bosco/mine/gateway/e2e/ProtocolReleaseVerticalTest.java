package com.bosco.mine.gateway.e2e;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.configuration.ConfigurationSnapshot;
import com.bosco.mine.gateway.configuration.InvalidConfigurationSnapshotException;
import com.bosco.mine.gateway.configuration.ProtocolSnapshotRegistry;
import com.bosco.mine.gateway.normalize.DeviceMessageNormalizer;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.protocol.engine.json.MqttJsonExecutionPlan;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolCompiler;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolDefinition;
import com.bosco.mine.protocol.engine.sample.ProtocolSample;
import com.bosco.mine.protocol.engine.sample.ProtocolSampleRunner;
import com.bosco.mine.protocol.engine.snapshot.ProtocolSnapshotHasher;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纵向校验黄金样本、配置快照热切换和 MQTT JSON 统一消息标准化使用同一执行语义。
 */
class ProtocolReleaseVerticalTest {

    private static final UUID TRUCK_PRODUCT_ID = UUID.randomUUID();
    private static final UUID FAN_PRODUCT_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-16T00:01:00Z"), ZoneOffset.UTC
    );
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final ProtocolSnapshotHasher hasher = new ProtocolSnapshotHasher();

    @Test
    void 三类厂商报文应热切换为统一遥测且坏快照和回滚不需要重启() throws Exception {
        Manifest flat = manifest("flat", 0);
        Manifest nested = manifest("nested", 0);
        Manifest enumScale = manifest("enum-scale", 0);
        assertGoldenSample(flat);
        assertGoldenSample(nested);
        assertGoldenSample(enumScale);

        ProtocolSnapshotRegistry protocols = new ProtocolSnapshotRegistry(true);
        DeviceMessageNormalizer normalizer = new DeviceMessageNormalizer(
                identities(), protocols, IdGenerator.timeOrdered(), CLOCK, 64 * 1024
        );

        ConfigurationSnapshot flatRelease = snapshot(TRUCK_PRODUCT_ID, flat, 1);
        protocols.activate(flatRelease);
        MessageEnvelope<UnifiedDeviceMessage> flatMessage = normalize(normalizer, flat, "flat-client");
        assertThat(flatMessage.payload().properties().values())
                .containsEntry("engineRpm", 1280)
                .containsEntry("vehicleSpeed", new BigDecimal("12.5"));

        ConfigurationSnapshot nestedRelease = snapshot(TRUCK_PRODUCT_ID, nested, 2);
        protocols.activate(nestedRelease);
        MessageEnvelope<UnifiedDeviceMessage> nestedMessage = normalize(normalizer, nested, "nested-client");
        assertThat(nestedMessage.payload().properties().values())
                .containsEntry("engineRpm", 1350)
                .containsEntry("coolantTemperature", new BigDecimal("82.5"));

        ConfigurationSnapshot corrupted = new ConfigurationSnapshot(
                UUID.randomUUID(), TRUCK_PRODUCT_ID, UUID.randomUUID(), 3,
                "sha256:" + "0".repeat(64), nestedRelease.snapshotJson()
        );
        assertThatThrownBy(() -> protocols.activate(corrupted))
                .isInstanceOf(InvalidConfigurationSnapshotException.class)
                .hasMessageContaining("哈希");
        assertThat(normalize(normalizer, nested, "nested-client").correlationId())
                .isEqualTo(nestedRelease.releaseId().toString());

        ConfigurationSnapshot fanRelease = snapshot(FAN_PRODUCT_ID, enumScale, 1);
        protocols.activate(fanRelease);
        MessageEnvelope<UnifiedDeviceMessage> fanMessage = normalize(normalizer, enumScale, "fan-client");
        assertThat(fanMessage.payload().properties().values())
                .containsEntry("motorCurrent", new BigDecimal("25.3"))
                .containsEntry("runningState", "RUNNING");

        ConfigurationSnapshot rollbackRelease = snapshot(TRUCK_PRODUCT_ID, flat, 4);
        protocols.activate(rollbackRelease);
        MessageEnvelope<UnifiedDeviceMessage> rollbackMessage = normalize(normalizer, flat, "flat-client");
        assertThat(rollbackMessage.correlationId()).isEqualTo(rollbackRelease.releaseId().toString());
        assertThat(rollbackMessage.payload().properties().values()).containsEntry("engineRpm", 1280);
    }

    private void assertGoldenSample(Manifest manifest) {
        MqttJsonExecutionPlan plan = new MqttJsonProtocolCompiler().compile(manifest.definition());
        ProtocolSampleRunner.TestReport report = new ProtocolSampleRunner().run(
                plan,
                new MqttJsonExecutionPlan.TrustedIdentity(manifest.productKey(), manifest.deviceCode()),
                List.of(manifest.sample())
        );
        assertThat(report.passed()).isTrue();
    }

    private MessageEnvelope<UnifiedDeviceMessage> normalize(
            DeviceMessageNormalizer normalizer,
            Manifest manifest,
            String clientId
    ) {
        return normalizer.normalize(
                "mine.v1.%s.%s.up.telemetry".formatted(manifest.productKey(), clientId),
                manifest.sample().payload()
        );
    }

    private ConfigurationSnapshot snapshot(UUID productId, Manifest manifest, long sequence) {
        try {
            String content = jsonMapper.writeValueAsString(Map.of(
                    "schemaVersion", 1,
                    "productId", productId,
                    "productKey", manifest.productKey(),
                    "modelVersionId", UUID.nameUUIDFromBytes((manifest.productKey() + "-model").getBytes()),
                    "protocolMappingVersionId", UUID.nameUUIDFromBytes((manifest.definition().protocolKey() + "-mapping").getBytes()),
                    "definition", manifest.definition()
            ));
            return new ConfigurationSnapshot(
                    UUID.randomUUID(), productId, UUID.randomUUID(), sequence,
                    hasher.sha256(content), content
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private DeviceSessionRegistry identities() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(
                identity("haul-truck", "flat-client", "truck-001"),
                identity("haul-truck", "nested-client", "truck-002"),
                identity("ventilation-fan", "fan-client", "fan-001")
        ));
        return registry;
    }

    private DeviceSessionRegistry.DeviceIdentity identity(String productKey, String clientId, String deviceCode) {
        return new DeviceSessionRegistry.DeviceIdentity(
                productKey, clientId, UUID.randomUUID().toString(), deviceCode,
                "mine-site-001", "legacy-not-used"
        );
    }

    private Manifest manifest(String directory, int sampleIndex) throws IOException {
        Path path = exampleRoot().resolve(directory).resolve("manifest.json");
        JsonNode root = jsonMapper.readTree(Files.readAllBytes(path));
        JsonNode trustedIdentity = root.get("trustedIdentity");
        JsonNode sample = root.get("samples").get(sampleIndex);
        try {
            MqttJsonProtocolDefinition definition = new MqttJsonProtocolDefinition(
                    root.get("protocolKey").asString(),
                    root.get("version").asInt(),
                    jsonMapper.treeToValue(root.get("metadata"), MqttJsonProtocolDefinition.MetadataPaths.class),
                    jsonMapper.convertValue(
                            root.get("properties"),
                            new TypeReference<List<MqttJsonProtocolDefinition.PropertyDefinition>>() { }
                    ),
                    jsonMapper.convertValue(
                            root.get("fields"),
                            new TypeReference<List<MqttJsonProtocolDefinition.FieldMapping>>() { }
                    )
            );
            ProtocolSample protocolSample = new ProtocolSample(
                    sample.get("name").asString(),
                    jsonMapper.writeValueAsBytes(sample.get("payload")),
                    sample.get("expectedSuccess").asBoolean(),
                    sample.has("expectedProperties")
                            ? jsonMapper.convertValue(sample.get("expectedProperties"), new TypeReference<>() { })
                            : Map.of(),
                    sample.has("expectedErrorCode") ? sample.get("expectedErrorCode").asString() : null
            );
            return new Manifest(
                    trustedIdentity.get("productKey").asString(),
                    trustedIdentity.get("deviceCode").asString(),
                    definition,
                    protocolSample
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("协议示例无法转换为执行定义: " + path, exception);
        }
    }

    private Path exampleRoot() {
        Path moduleRelative = Path.of("..", "examples", "protocols", "mqtt-json").normalize();
        if (Files.isDirectory(moduleRelative)) return moduleRelative;
        Path reactorRelative = Path.of("examples", "protocols", "mqtt-json");
        if (Files.isDirectory(reactorRelative)) return reactorRelative;
        throw new IllegalStateException("找不到 MQTT JSON 协议示例目录");
    }

    /** 测试使用的协议示例定义与首条黄金样本。 */
    private record Manifest(
            String productKey,
            String deviceCode,
            MqttJsonProtocolDefinition definition,
            ProtocolSample sample
    ) {
    }
}
