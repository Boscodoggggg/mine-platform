package com.bosco.mine.gateway.location;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.normalize.InvalidDeviceMessageException;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.model.location.LocationObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验定位上行的凭据身份、固定协议与输入边界。
 */
class LocationMessageNormalizerTest {

    private static final String DEVICE_ID = "019f63e2-071f-706a-bd12-4dad7150e75d";
    private static final String ROUTING_KEY =
            "mine.v1.haul-truck.device-credential-001.up.location";
    private static final UUID OBSERVATION_ID =
            UUID.fromString("019f6d20-0000-7000-8000-000000000001");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T08:00:02Z"), ZoneOffset.UTC);

    private LocationMessageNormalizer normalizer;

    @BeforeEach
    void setUp() {
        DeviceSessionRegistry registry = registry();
        IdGenerator idGenerator = mock(IdGenerator.class);
        when(idGenerator.next()).thenReturn(OBSERVATION_ID);
        normalizer = new LocationMessageNormalizer(registry, idGenerator, CLOCK, 2048);
    }

    @Test
    void 应只使用凭据快照的设备标识生成定位观测() {
        LocationObservation observation = normalizer.normalize(ROUTING_KEY, validPayload());

        assertThat(observation.observationId()).isEqualTo(OBSERVATION_ID);
        assertThat(observation.sourceDeviceId()).isEqualTo(UUID.fromString(DEVICE_ID));
        assertThat(observation.tagCode()).isEqualTo("tag-001");
        assertThat(observation.coordinateSystemCode()).isEqualTo("uwb-local");
        assertThat(observation.x()).isEqualTo(128.5D);
        assertThat(observation.y()).isEqualTo(64.25D);
        assertThat(observation.z()).isEqualTo(12D);
        assertThat(observation.bootId()).isEqualTo("simulator-boot-001");
        assertThat(observation.sequence()).isEqualTo(7L);
        assertThat(observation.occurredAt()).isEqualTo(Instant.parse("2026-07-18T08:00:00Z"));
        assertThat(observation.receivedAt()).isEqualTo(Instant.parse("2026-07-18T08:00:02Z"));
    }

    @Test
    void 主题中产品或客户端与凭据快照不一致时应拒绝() {
        assertThatThrownBy(() -> normalizer.normalize(
                "mine.v1.loader.device-credential-001.up.location", validPayload()))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("未登记");
        assertThatThrownBy(() -> normalizer.normalize(
                "mine.v1.haul-truck.other-client.up.location", validPayload()))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("未登记");
    }

    @Test
    void 载荷不得冒充产品或业务设备() {
        assertThatThrownBy(() -> normalizer.normalize(
                ROUTING_KEY, payload("loader", "simulator-001", "128.5")))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("productKey");
        assertThatThrownBy(() -> normalizer.normalize(
                ROUTING_KEY, payload("haul-truck", "another-device", "128.5")))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("deviceCode");
    }

    @Test
    void 应拒绝非有限坐标和未知字段() {
        assertThatThrownBy(() -> normalizer.normalize(
                ROUTING_KEY, payload("haul-truck", "simulator-001", "1e309")))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("坐标");
        assertThatThrownBy(() -> normalizer.normalize(
                ROUTING_KEY,
                new String(validPayload(), StandardCharsets.UTF_8)
                        .replace("\"occurredAt\"", "\"unknown\":true,\"occurredAt\"")
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void 应在JSON解析前拒绝超限报文() {
        LocationMessageNormalizer limited = new LocationMessageNormalizer(
                registry(), mock(IdGenerator.class), CLOCK, 32);

        assertThatThrownBy(() -> limited.normalize(ROUTING_KEY, validPayload()))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("超过");
    }

    @Test
    void 应只接受定位JSON协议第一版() {
        byte[] version2 = new String(validPayload(), StandardCharsets.UTF_8)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> normalizer.normalize(ROUTING_KEY, version2))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void 应拒绝可能导致解析歧义的重复字段() {
        byte[] duplicatedIdentity = new String(validPayload(), StandardCharsets.UTF_8)
                .replace(
                        "\"productKey\":\"haul-truck\"",
                        "\"productKey\":\"loader\",\"productKey\":\"haul-truck\"")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> normalizer.normalize(ROUTING_KEY, duplicatedIdentity))
                .isInstanceOf(InvalidDeviceMessageException.class)
                .hasMessageContaining("JSON");
    }

    private DeviceSessionRegistry registry() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck", "device-credential-001", DEVICE_ID,
                "simulator-001", "mine-site-001", "mqtt-json-v1")));
        return registry;
    }

    private byte[] validPayload() {
        return payload("haul-truck", "simulator-001", "128.5");
    }

    private byte[] payload(String productKey, String deviceCode, String x) {
        return ("""
                {
                  "schemaVersion":1,
                  "productKey":"%s",
                  "deviceCode":"%s",
                  "tagCode":"tag-001",
                  "coordinateSystemCode":"uwb-local",
                  "x":%s,
                  "y":64.25,
                  "z":12.0,
                  "speedMetersPerSecond":8.5,
                  "headingDegrees":180.0,
                  "accuracyMeters":0.3,
                  "bootId":"simulator-boot-001",
                  "sequence":7,
                  "occurredAt":"2026-07-18T08:00:00Z"
                }
                """.formatted(productKey, deviceCode, x)).getBytes(StandardCharsets.UTF_8);
    }
}
