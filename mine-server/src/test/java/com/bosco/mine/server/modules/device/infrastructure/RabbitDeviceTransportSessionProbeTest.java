package com.bosco.mine.server.modules.device.infrastructure;

import com.bosco.mine.server.modules.device.model.DeviceCredential;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验 MQTT 设备活动凭据与 RabbitMQ 会话的关联检查。
 */
class RabbitDeviceTransportSessionProbeTest {

    @Test
    void 活动凭据存在Broker连接时应返回通过且不暴露远端地址() {
        DeviceMapper mapper = mock(DeviceMapper.class);
        RabbitUserAdminClient rabbit = mock(RabbitUserAdminClient.class);
        UUID deviceId = UUID.randomUUID();
        Instant connectedAt = Instant.parse("2026-07-16T07:59:55Z");
        when(mapper.findActiveCredentialByDeviceId(deviceId)).thenReturn(Optional.of(credential(deviceId)));
        when(rabbit.findConnections("device-user")).thenReturn(List.of(
                new RabbitUserAdminClient.ConnectionSummary(connectedAt)
        ));
        RabbitDeviceTransportSessionProbe probe = new RabbitDeviceTransportSessionProbe(mapper, rabbit);

        DeviceTransportSessionProbe.Result result =
                probe.probe(deviceId, "DEVICE_TO_PLATFORM_MQTT");

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.message()).isEqualTo("TRANSPORT_CONNECTED");
        assertThat(result.observedAt()).isEqualTo(connectedAt);
    }

    @Test
    void 非Mqtt连接模式没有会话来源时应返回未知() {
        RabbitDeviceTransportSessionProbe probe = new RabbitDeviceTransportSessionProbe(
                mock(DeviceMapper.class), mock(RabbitUserAdminClient.class)
        );

        DeviceTransportSessionProbe.Result result =
                probe.probe(UUID.randomUUID(), "PLATFORM_TO_DEVICE_TCP");

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).isEqualTo("TRANSPORT_SOURCE_UNAVAILABLE");
    }

    @Test
    void 没有生效凭据时应显示等待而非失败() {
        DeviceMapper mapper = mock(DeviceMapper.class);
        UUID deviceId = UUID.randomUUID();
        when(mapper.findActiveCredentialByDeviceId(deviceId)).thenReturn(Optional.empty());
        RabbitDeviceTransportSessionProbe probe = new RabbitDeviceTransportSessionProbe(
                mapper, mock(RabbitUserAdminClient.class)
        );

        DeviceTransportSessionProbe.Result result =
                probe.probe(deviceId, "DEVICE_TO_PLATFORM_MQTT");

        assertThat(result.status()).isEqualTo("WAITING");
        assertThat(result.message()).isEqualTo("CREDENTIAL_NOT_ACTIVE");
    }

    @Test
    void Broker管理面不可用时应降级为未知() {
        DeviceMapper mapper = mock(DeviceMapper.class);
        RabbitUserAdminClient rabbit = mock(RabbitUserAdminClient.class);
        UUID deviceId = UUID.randomUUID();
        when(mapper.findActiveCredentialByDeviceId(deviceId)).thenReturn(Optional.of(credential(deviceId)));
        when(rabbit.findConnections("device-user")).thenThrow(new IllegalStateException("管理面不可用"));
        RabbitDeviceTransportSessionProbe probe = new RabbitDeviceTransportSessionProbe(mapper, rabbit);

        DeviceTransportSessionProbe.Result result =
                probe.probe(deviceId, "DEVICE_TO_PLATFORM_MQTT");

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).isEqualTo("TRANSPORT_PROBE_FAILED");
    }

    private DeviceCredential credential(UUID deviceId) {
        return new DeviceCredential(
                UUID.randomUUID(), deviceId, "device-user", "hash", "hint", "topic", "ACTIVE",
                Instant.parse("2026-07-16T07:00:00Z"), 0
        );
    }
}
