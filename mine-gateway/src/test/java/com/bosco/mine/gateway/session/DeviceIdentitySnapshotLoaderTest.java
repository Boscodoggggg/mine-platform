package com.bosco.mine.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验设备凭据身份快照可以原子加载和热替换。
 */
class DeviceIdentitySnapshotLoaderTest {

    private static final String DEVICE_ID_1 = "019f63e2-071f-706a-bd12-4dad7150e75d";
    private static final String DEVICE_ID_2 = "019f63e2-071f-706a-bd12-4dad7150e75e";

    @TempDir
    private Path tempDirectory;

    @Test
    void 重新加载时应整体替换旧身份且不保留已撤销设备() throws Exception {
        Path snapshot = tempDirectory.resolve("device-identities.json");
        Files.writeString(snapshot, snapshot("client-001", DEVICE_ID_1, "device-001"));
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        DeviceIdentitySnapshotLoader loader = new DeviceIdentitySnapshotLoader(registry, snapshot);

        loader.reload();

        assertThat(registry.find("haul-truck", "client-001"))
                .get()
                .extracting(DeviceSessionRegistry.DeviceIdentity::deviceId)
                .isEqualTo(DEVICE_ID_1);

        Files.writeString(snapshot, snapshot("client-002", DEVICE_ID_2, "device-002"));
        loader.reload();

        assertThat(registry.find("haul-truck", "client-001")).isEmpty();
        assertThat(registry.find("haul-truck", "client-002"))
                .get()
                .extracting(DeviceSessionRegistry.DeviceIdentity::deviceId)
                .isEqualTo(DEVICE_ID_2);
    }

    private String snapshot(String clientId, String deviceId, String deviceCode) {
        return """
                {
                  "version": 1,
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
                """.formatted(clientId, deviceId, deviceCode);
    }
}
