package com.bosco.mine.gateway.configuration;

import com.bosco.mine.protocol.engine.binding.ProductProtocolBinding;
import com.bosco.mine.protocol.engine.snapshot.ProtocolSnapshotHasher;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 Gateway 配置快照在哈希和编译通过后才按单调序号原子切换。
 */
class ProtocolSnapshotRegistryTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private final ProtocolSnapshotHasher hasher = new ProtocolSnapshotHasher();
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void 合法高版本应激活且相同发布应幂等() {
        ProtocolSnapshotRegistry registry = new ProtocolSnapshotRegistry(true);
        ConfigurationSnapshot snapshot = snapshot(3, validSnapshot("rpm", "engineRpm"));

        assertThat(registry.activate(snapshot))
                .isEqualTo(ProtocolSnapshotRegistry.ActivationResult.ACTIVATED);
        assertThat(registry.activate(snapshot))
                .isEqualTo(ProtocolSnapshotRegistry.ActivationResult.UNCHANGED);
        ProtocolSnapshotRegistry.ActiveProtocol active = registry.find("haul-truck").orElseThrow();
        assertThat(active.releaseSequence()).isEqualTo(3);
        assertThat(active.plan().protocolKey()).isEqualTo("haul-truck-json");
        assertThat(active.plan().fields()).hasSize(1);
    }

    @Test
    void 低序号或同序号不同内容必须拒绝() {
        ProtocolSnapshotRegistry registry = new ProtocolSnapshotRegistry(true);
        registry.activate(snapshot(5, validSnapshot("rpm", "engineRpm")));

        assertThatThrownBy(() -> registry.activate(snapshot(4, validSnapshot("rpm", "engineRpm"))))
                .isInstanceOf(InvalidConfigurationSnapshotException.class)
                .hasMessageContaining("低于");
        assertThatThrownBy(() -> registry.activate(snapshot(5, validSnapshot("speed", "engineRpm"))))
                .isInstanceOf(InvalidConfigurationSnapshotException.class)
                .hasMessageContaining("相同序号");
    }

    @Test
    void 哈希错误或定义无法编译时必须保留上一份可用配置() {
        ProtocolSnapshotRegistry registry = new ProtocolSnapshotRegistry(true);
        ConfigurationSnapshot stable = snapshot(2, validSnapshot("rpm", "engineRpm"));
        registry.activate(stable);
        ConfigurationSnapshot corrupted = new ConfigurationSnapshot(
                UUID.randomUUID(), PRODUCT_ID, UUID.randomUUID(), 3,
                "sha256:" + "0".repeat(64), validSnapshot("speed", "engineRpm")
        );

        assertThatThrownBy(() -> registry.activate(corrupted))
                .isInstanceOf(InvalidConfigurationSnapshotException.class)
                .hasMessageContaining("哈希");
        assertThatThrownBy(() -> registry.activate(snapshot(3, validSnapshot("speed", "missingProperty"))))
                .isInstanceOf(InvalidConfigurationSnapshotException.class)
                .hasMessageContaining("编译");
        assertThat(registry.find("haul-truck").orElseThrow().releaseId())
                .isEqualTo(stable.releaseId());
    }

    @Test
    void 强制模式下产品尚无配置时应明确拒绝解析() {
        ProtocolSnapshotRegistry registry = new ProtocolSnapshotRegistry(true);

        assertThatThrownBy(() -> registry.require("haul-truck"))
                .isInstanceOf(InvalidConfigurationSnapshotException.class)
                .hasMessageContaining("尚未加载");
    }

    @Test
    void 应激活schema2的MQTT二进制产品绑定() {
        ProtocolSnapshotRegistry registry = new ProtocolSnapshotRegistry(true);

        registry.activate(snapshot(6, binarySnapshot()));

        ProtocolSnapshotRegistry.ActiveProtocol active = registry.require("haul-truck");
        assertThat(active.binding().transport())
                .isEqualTo(ProductProtocolBinding.Transport.MQTT);
        assertThat(active.binding().payloadFormat())
                .isEqualTo(ProductProtocolBinding.PayloadFormat.BINARY);
        assertThat(active.binding().pluginKey()).isEqualTo("haul-truck-mqtt-binary");
        assertThat(active.plan()).isNull();
        assertThat(active.protocolVersion()).isEqualTo("haul-truck-mqtt-binary:v1");
    }

    @Test
    void schema2配置解析必须携带定义且失败不能覆盖旧配置() {
        ProtocolSnapshotRegistry registry = new ProtocolSnapshotRegistry(true);
        ConfigurationSnapshot stable = snapshot(2, binarySnapshot());
        registry.activate(stable);

        assertThatThrownBy(() -> registry.activate(snapshot(3, invalidConfigurationSnapshot())))
                .isInstanceOf(InvalidConfigurationSnapshotException.class)
                .hasMessageContaining("定义");
        assertThat(registry.require("haul-truck").releaseId()).isEqualTo(stable.releaseId());
    }

    private ConfigurationSnapshot snapshot(long sequence, String snapshotJson) {
        return new ConfigurationSnapshot(
                UUID.randomUUID(), PRODUCT_ID, UUID.randomUUID(), sequence,
                hasher.sha256(snapshotJson), snapshotJson
        );
    }

    private String validSnapshot(String sourcePath, String targetIdentifier) {
        try {
            return jsonMapper.writeValueAsString(jsonMapper.readTree("""
                    {
                      "schemaVersion":1,
                      "productId":"%s",
                      "productKey":"haul-truck",
                      "modelVersionId":"%s",
                      "protocolMappingVersionId":"%s",
                      "definition":{
                        "protocolKey":"haul-truck-json",
                        "version":2,
                        "metadata":{
                          "productKeyPath":"productKey",
                          "deviceCodePath":"deviceCode",
                          "bootIdPath":"bootId",
                          "sequencePath":"sequence",
                          "occurredAtPath":"occurredAt",
                          "replayedPath":"replayed"
                        },
                        "properties":[{"identifier":"engineRpm","dataType":"INTEGER"}],
                        "fields":[{
                          "sourcePath":"%s",
                          "sourceType":"INTEGER",
                          "targetIdentifier":"%s",
                          "transform":"NONE",
                          "scaleFactor":null,
                          "offsetValue":null,
                          "enumMapping":{},
                          "requiredField":true
                        }]
                      }
                    }
                    """.formatted(
                    PRODUCT_ID, UUID.randomUUID(), UUID.randomUUID(), sourcePath, targetIdentifier)));
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String binarySnapshot() {
        return normalized("""
                {
                  "schemaVersion":2,
                  "productId":"%s",
                  "productKey":"haul-truck",
                  "modelVersionId":"%s",
                  "protocolMappingVersionId":"%s",
                  "binding":{
                    "transport":"MQTT",
                    "payloadFormat":"BINARY",
                    "routingMode":"TRUSTED_IDENTITY",
                    "parserType":"JAVA_PLUGIN",
                    "pluginKey":"haul-truck-mqtt-binary",
                    "pluginVersion":"haul-truck-mqtt-binary:v1"
                  }
                }
                """.formatted(PRODUCT_ID, UUID.randomUUID(), UUID.randomUUID()));
    }

    private String invalidConfigurationSnapshot() {
        return normalized("""
                {
                  "schemaVersion":2,
                  "productId":"%s",
                  "productKey":"haul-truck",
                  "modelVersionId":"%s",
                  "protocolMappingVersionId":"%s",
                  "binding":{
                    "transport":"MQTT",
                    "payloadFormat":"JSON",
                    "routingMode":"TRUSTED_IDENTITY",
                    "parserType":"CONFIGURATION",
                    "pluginKey":null,
                    "pluginVersion":null
                  }
                }
                """.formatted(PRODUCT_ID, UUID.randomUUID(), UUID.randomUUID()));
    }

    private String normalized(String json) {
        try {
            return jsonMapper.writeValueAsString(jsonMapper.readTree(json));
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
