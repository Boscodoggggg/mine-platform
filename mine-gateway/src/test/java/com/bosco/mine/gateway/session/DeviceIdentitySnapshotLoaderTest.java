package com.bosco.mine.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 校验设备凭据身份快照可以原子加载和热替换。
 */
class DeviceIdentitySnapshotLoaderTest {

    private static final String DEVICE_ID_1 = "019f63e2-071f-706a-bd12-4dad7150e75d";
    private static final String DEVICE_ID_2 = "019f63e2-071f-706a-bd12-4dad7150e75e";

    @TempDir
    private Path tempDirectory;

    @Test
    void 更高版本应整体替换旧身份并记录原始文件摘要() throws Exception {
        Path snapshot = tempDirectory.resolve("device-identities.json");
        String version1 = snapshot(1, "client-001", DEVICE_ID_1, "device-001");
        Files.writeString(snapshot, version1);
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        DeviceIdentitySnapshotLoader loader = new DeviceIdentitySnapshotLoader(registry, snapshot);

        assertThat(loader.reload()).isEqualTo(DeviceSessionRegistry.ActivationResult.ACTIVATED);

        assertThat(registry.find("haul-truck", "client-001"))
                .get()
                .extracting(DeviceSessionRegistry.DeviceIdentity::deviceId)
                .isEqualTo(DEVICE_ID_1);
        assertThat(registry.status())
                .isEqualTo(new DeviceSessionRegistry.SnapshotStatus(1, sha256(version1), 1));

        String version2 = snapshot(2, "client-002", DEVICE_ID_2, "device-002");
        Files.writeString(snapshot, version2);
        assertThat(loader.reload()).isEqualTo(DeviceSessionRegistry.ActivationResult.ACTIVATED);

        assertThat(registry.find("haul-truck", "client-001")).isEmpty();
        assertThat(registry.find("haul-truck", "client-002"))
                .get()
                .extracting(DeviceSessionRegistry.DeviceIdentity::deviceId)
                .isEqualTo(DEVICE_ID_2);
        assertThat(registry.status())
                .isEqualTo(new DeviceSessionRegistry.SnapshotStatus(2, sha256(version2), 1));
    }

    @Test
    void 同版本同文件应视为幂等重复() throws Exception {
        Path snapshot = tempDirectory.resolve("device-identities.json");
        Files.writeString(snapshot, snapshot(2, "client-002", DEVICE_ID_2, "device-002"));
        DeviceIdentitySnapshotLoader loader = new DeviceIdentitySnapshotLoader(
                new DeviceSessionRegistry(), snapshot
        );

        assertThat(loader.reload()).isEqualTo(DeviceSessionRegistry.ActivationResult.ACTIVATED);
        assertThat(loader.reload()).isEqualTo(DeviceSessionRegistry.ActivationResult.UNCHANGED);
    }

    @Test
    void 同版本异内容和低版本应拒绝且保留上一有效快照() throws Exception {
        Path snapshot = tempDirectory.resolve("device-identities.json");
        String version2 = snapshot(2, "client-002", DEVICE_ID_2, "device-002");
        Files.writeString(snapshot, version2);
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        DeviceIdentitySnapshotLoader loader = new DeviceIdentitySnapshotLoader(registry, snapshot);
        loader.reload();

        Files.writeString(snapshot, snapshot(2, "client-001", DEVICE_ID_1, "device-001"));
        assertThatIllegalArgumentException()
                .isThrownBy(loader::reload)
                .withMessageContaining("相同版本");
        assertLastKnownGood(registry, version2);

        Files.writeString(snapshot, snapshot(1, "client-001", DEVICE_ID_1, "device-001"));
        assertThatIllegalArgumentException()
                .isThrownBy(loader::reload)
                .withMessageContaining("低于当前版本");
        assertLastKnownGood(registry, version2);
    }

    @Test
    void 超出long范围的版本应明确拒绝() throws Exception {
        Path snapshot = tempDirectory.resolve("device-identities.json");
        Files.writeString(snapshot, """
                {"version":9223372036854775808,"devices":[]}
                """);
        DeviceIdentitySnapshotLoader loader = new DeviceIdentitySnapshotLoader(
                new DeviceSessionRegistry(), snapshot
        );

        assertThatIllegalArgumentException()
                .isThrownBy(loader::reload)
                .withMessageContaining("正整数");
    }

    private void assertLastKnownGood(DeviceSessionRegistry registry, String content) {
        assertThat(registry.find("haul-truck", "client-002")).isPresent();
        assertThat(registry.find("haul-truck", "client-001")).isEmpty();
        assertThat(registry.status())
                .isEqualTo(new DeviceSessionRegistry.SnapshotStatus(2, sha256(content), 1));
    }

    private String snapshot(long version, String clientId, String deviceId, String deviceCode) {
        return """
                {
                  "version": %d,
                  "devices": [
                    {
                      "productKey": "haul-truck",
                      "clientId": "%s",
                      "deviceId": "%s",
                      "deviceCode": "%s",
                      "tenantScope": "mine-site-001",
                      "protocolVersion": "mqtt-json-v1"
                    }
                  ]
                }
                """.formatted(version, clientId, deviceId, deviceCode);
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
