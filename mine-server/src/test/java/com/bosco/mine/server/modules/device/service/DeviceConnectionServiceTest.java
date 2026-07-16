package com.bosco.mine.server.modules.device.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionProfileRequest;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionProfileResponse;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.infrastructure.DeviceConnectionProbe;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
import com.bosco.mine.server.modules.device.repository.DeviceConnectionProfileMapper;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验设备接入配置保存和分层诊断业务规则。
 */
class DeviceConnectionServiceTest {

    private DeviceConnectionProfileMapper profileMapper;
    private DeviceMapper deviceMapper;
    private DeviceConnectionProbe probe;
    private OperationAuditService auditService;
    private DeviceConnectionService service;
    private UUID deviceId;

    @BeforeEach
    void 创建设备接入服务测试环境() {
        profileMapper = mock(DeviceConnectionProfileMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        probe = mock(DeviceConnectionProbe.class);
        auditService = mock(OperationAuditService.class);
        service = new DeviceConnectionService(profileMapper, deviceMapper, probe, auditService, new ObjectMapper());
        deviceId = UUID.randomUUID();
        when(deviceMapper.findDeviceById(deviceId)).thenReturn(Optional.of(device(deviceId)));
    }

    @Test
    void 首次保存应创建不含凭据的接入配置() {
        when(profileMapper.findByDeviceId(deviceId)).thenReturn(Optional.empty());
        when(profileMapper.findByDeviceId(deviceId))
                .thenReturn(Optional.empty(), Optional.of(profile(deviceId, "NOT_TESTED", 0)));

        DeviceConnectionProfileResponse response = service.save(deviceId, request(0));

        assertThat(response.host()).isEqualTo("mqtt.mine.local");
        assertThat(response.lastTestStatus()).isEqualTo("NOT_TESTED");
        verify(profileMapper).insert(any(DeviceConnectionProfile.class), any());
    }

    @Test
    void 不存在的设备不得保存接入配置() {
        UUID missing = UUID.randomUUID();
        when(deviceMapper.findDeviceById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(missing, request(0)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("设备不存在");
    }

    @Test
    void 设备主动接入时端点通过仍应要求首帧确认() {
        DeviceConnectionProfile profile = profile(deviceId, "NOT_TESTED", 2);
        when(profileMapper.findByDeviceId(deviceId)).thenReturn(Optional.of(profile));
        when(probe.probe(profile)).thenReturn(List.of(
                new DeviceConnectionTestResponse.Stage("DNS", "PASSED", "DNS 解析成功", 2),
                new DeviceConnectionTestResponse.Stage("TCP", "PASSED", "TCP 连接成功", 4),
                new DeviceConnectionTestResponse.Stage("TLS", "PASSED", "TLS 握手成功", 8)
        ));
        when(profileMapper.updateTestResult(
                eq(profile.id()), eq("PARTIAL"), any(Instant.class), any(Long.class),
                anyString(), anyString(), eq(2L), any()
        )).thenReturn(1);

        DeviceConnectionTestResponse response = service.test(deviceId);

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.stages()).extracting(DeviceConnectionTestResponse.Stage::stage)
                .containsExactly("CONFIG", "DNS", "TCP", "TLS", "DEVICE_CONFIRMATION");
        assertThat(response.stages().getLast().status()).isEqualTo("SKIPPED");
        assertThat(response.message()).isEqualTo("TEST_PARTIAL");
        verify(profileMapper).updateTestResult(
                eq(profile.id()), eq("PARTIAL"), any(Instant.class), any(Long.class),
                anyString(), anyString(), eq(2L), any()
        );
    }

    @Test
    void 平台主动连接且未启用Tls时应判定诊断通过() {
        DeviceConnectionProfile profile = new DeviceConnectionProfile(
                UUID.randomUUID(), deviceId, "PLATFORM_TO_DEVICE_TCP", "10.0.0.8", 502,
                false, null, 5000, 60, 180,
                "NOT_TESTED", null, null, null, "[]", Instant.parse("2026-07-16T00:00:00Z"),
                Instant.parse("2026-07-16T00:00:00Z"), 3
        );
        when(profileMapper.findByDeviceId(deviceId)).thenReturn(Optional.of(profile));
        when(probe.probe(profile)).thenReturn(List.of(
                new DeviceConnectionTestResponse.Stage("DNS", "PASSED", "DNS_OK", 2),
                new DeviceConnectionTestResponse.Stage("TCP", "PASSED", "TCP_OK", 4),
                new DeviceConnectionTestResponse.Stage("TLS", "SKIPPED", "TLS_DISABLED", 0)
        ));
        when(profileMapper.updateTestResult(
                eq(profile.id()), eq("PASSED"), any(Instant.class), any(Long.class),
                eq("TEST_PASSED"), anyString(), eq(3L), any()
        )).thenReturn(1);

        DeviceConnectionTestResponse response = service.test(deviceId);

        assertThat(response.status()).isEqualTo("PASSED");
        assertThat(response.message()).isEqualTo("TEST_PASSED");
        assertThat(response.stages()).extracting(DeviceConnectionTestResponse.Stage::stage)
                .containsExactly("CONFIG", "DNS", "TCP", "TLS", "DEVICE_CONFIRMATION");
        assertThat(response.stages().getLast())
                .extracting(DeviceConnectionTestResponse.Stage::status, DeviceConnectionTestResponse.Stage::message)
                .containsExactly("PASSED", "ACTIVE_ENDPOINT_CONFIRMED");
    }

    private DeviceConnectionProfileRequest request(long version) {
        return new DeviceConnectionProfileRequest(
                "DEVICE_TO_PLATFORM_MQTT", " mqtt.mine.local ", 8883,
                true, "mqtt.mine.local", 5000, 60, 180, version
        );
    }

    private DeviceConnectionProfile profile(UUID targetDeviceId, String testStatus, long version) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new DeviceConnectionProfile(
                UUID.randomUUID(), targetDeviceId, "DEVICE_TO_PLATFORM_MQTT", "mqtt.mine.local", 8883,
                true, "mqtt.mine.local", 5000, 60, 180,
                testStatus, null, null, null, "[]", now, now, version
        );
    }

    private Device device(UUID targetDeviceId) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new Device(targetDeviceId, UUID.randomUUID(), "truck-001", "一号矿卡", "ENABLED",
                null, now, now, 0);
    }
}
