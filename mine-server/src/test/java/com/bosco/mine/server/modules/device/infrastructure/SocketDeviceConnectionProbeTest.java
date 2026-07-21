package com.bosco.mine.server.modules.device.infrastructure;

import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用本机临时端口校验 DNS 和 TCP 分层探针。
 */
class SocketDeviceConnectionProbeTest {

    private final SocketDeviceConnectionProbe probe = new SocketDeviceConnectionProbe(
            new DeviceProbeTargetPolicy("127.0.0.0/8,::1/128", true), 2
    );

    @Test
    void 可监听端口应通过域名和Tcp检查并跳过Tls() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<DeviceConnectionTestResponse.Stage> stages = probe.probe(profile(server.getLocalPort(), false));

            assertThat(stages).extracting(DeviceConnectionTestResponse.Stage::stage)
                    .containsExactly("DNS", "TCP", "TLS");
        assertThat(stages).extracting(DeviceConnectionTestResponse.Stage::status)
                .containsExactly("PASSED", "PASSED", "SKIPPED");
        assertThat(stages).extracting(DeviceConnectionTestResponse.Stage::message)
                .containsExactly("DNS_OK", "TCP_OK", "TLS_DISABLED");
        }
    }

    @Test
    void 关闭端口应失败Tcp并跳过后续Tls() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }

        List<DeviceConnectionTestResponse.Stage> stages = probe.probe(profile(closedPort, true));

        assertThat(stages).extracting(DeviceConnectionTestResponse.Stage::status)
                .containsExactly("PASSED", "FAILED", "SKIPPED");
        assertThat(stages.get(1).message()).doesNotContain("Exception").doesNotContain("java.");
    }

    private DeviceConnectionProfile profile(int port, boolean tls) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new DeviceConnectionProfile(
                UUID.randomUUID(), UUID.randomUUID(), "PLATFORM_TO_DEVICE_TCP", "localhost", port,
                tls, "localhost", 1000, 60, 180, "NOT_TESTED", null, null, null, "[]", now, now, 0
        );
    }
}
