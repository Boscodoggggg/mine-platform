package com.bosco.mine.server.modules.device.service;

import com.bosco.mine.server.modules.device.dto.DeviceAccessStatusResponse;
import com.bosco.mine.server.modules.device.dto.DeviceAccessTestResponse;
import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.infrastructure.DeviceTransportSessionProbe;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;
import com.bosco.mine.server.modules.device.repository.DeviceActivityRepository;
import com.bosco.mine.server.modules.device.repository.DeviceConnectionProfileMapper;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.strategy.service.EffectiveOnlineStatusPolicyProvider;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验设备四层接入状态和手动检查的总体结论。
 */
class DeviceAccessStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");
    private DeviceMapper deviceMapper;
    private DeviceConnectionProfileMapper profileMapper;
    private DeviceActivityRepository activityRepository;
    private DeviceTransportSessionProbe transportProbe;
    private DeviceConnectionService connectionService;
    private OperationAuditService auditService;
    private EffectiveOnlineStatusPolicyProvider policyProvider;
    private UUID deviceId;

    @BeforeEach
    void 创建设备接入状态测试环境() {
        deviceMapper = mock(DeviceMapper.class);
        profileMapper = mock(DeviceConnectionProfileMapper.class);
        activityRepository = mock(DeviceActivityRepository.class);
        transportProbe = mock(DeviceTransportSessionProbe.class);
        connectionService = mock(DeviceConnectionService.class);
        auditService = mock(OperationAuditService.class);
        policyProvider = mock(EffectiveOnlineStatusPolicyProvider.class);
        deviceId = UUID.randomUUID();
        when(deviceMapper.findDeviceById(deviceId)).thenReturn(Optional.of(device()));
        when(profileMapper.findByDeviceId(deviceId)).thenReturn(Optional.of(profile()));
        when(deviceMapper.findDevicesByIds(List.of(deviceId))).thenReturn(List.of(device()));
        when(profileMapper.findByDeviceIds(List.of(deviceId))).thenReturn(List.of(profile()));
        when(policyProvider.resolveThresholds(List.of(deviceId))).thenReturn(Map.of());
    }

    @Test
    void 最近有效报文未超过离线阈值时应显示在线() {
        when(activityRepository.findByDeviceIds(List.of(deviceId))).thenReturn(Map.of(
                deviceId, new DeviceActivityRepository.ActivityEvidence(
                        NOW.minusSeconds(600), NOW.minusSeconds(30)
                )
        ));
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        DeviceAccessStatusResponse response = service.find(List.of(deviceId)).getFirst();

        assertThat(response.status()).isEqualTo("ONLINE");
        assertThat(response.firstSeenAt()).isEqualTo(NOW.minusSeconds(600));
        assertThat(response.lastReceivedAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(response.message()).isEqualTo("ACCESS_ONLINE");
    }

    @Test
    void 已发布在线状态策略应覆盖接入配置中的离线阈值() {
        when(activityRepository.findByDeviceIds(List.of(deviceId))).thenReturn(Map.of(
                deviceId, new DeviceActivityRepository.ActivityEvidence(
                        NOW.minusSeconds(600), NOW.minusSeconds(30)
                )
        ));
        when(policyProvider.resolveThresholds(List.of(deviceId))).thenReturn(Map.of(deviceId, 20));
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        DeviceAccessStatusResponse response = service.find(List.of(deviceId)).getFirst();

        assertThat(response.status()).isEqualTo("STALE");
        assertThat(response.offlineThresholdSeconds()).isEqualTo(20);
    }

    @Test
    void 批量状态查询应一次读取台账接入配置和有效策略() {
        UUID anotherDeviceId = UUID.randomUUID();
        Device anotherDevice = device(anotherDeviceId);
        DeviceConnectionProfile anotherProfile = profile(anotherDeviceId);
        List<UUID> ids = List.of(deviceId, anotherDeviceId);
        when(deviceMapper.findDevicesByIds(ids)).thenReturn(List.of(device(), anotherDevice));
        when(profileMapper.findByDeviceIds(ids)).thenReturn(List.of(profile(), anotherProfile));
        when(policyProvider.resolveThresholds(ids)).thenReturn(Map.of());
        when(activityRepository.findByDeviceIds(ids)).thenReturn(Map.of());
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        assertThat(service.find(ids)).hasSize(2);

        verify(deviceMapper).findDevicesByIds(ids);
        verify(profileMapper).findByDeviceIds(ids);
        verify(policyProvider).resolveThresholds(ids);
        verify(deviceMapper, never()).findDeviceById(anotherDeviceId);
        verify(profileMapper, never()).findByDeviceId(anotherDeviceId);
    }

    @Test
    void 手动检查发现Broker已连接但没有有效首帧时应显示仅传输连接() {
        when(activityRepository.findByDeviceIds(List.of(deviceId))).thenReturn(Map.of());
        when(connectionService.test(deviceId)).thenReturn(new DeviceConnectionTestResponse(
                deviceId, "PARTIAL", NOW, 5, "TEST_PARTIAL", List.of()
        ));
        when(transportProbe.probe(deviceId, "DEVICE_TO_PLATFORM_MQTT")).thenReturn(
                new DeviceTransportSessionProbe.Result("PASSED", "TRANSPORT_CONNECTED", NOW.minusSeconds(5))
        );
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        DeviceAccessTestResponse response = service.test(deviceId);

        assertThat(response.status()).isEqualTo("TRANSPORT_ONLY");
        assertThat(response.stages()).extracting(DeviceAccessTestResponse.Stage::layer)
                .containsExactly("ENDPOINT", "TRANSPORT", "ACTIVITY", "DATA");
        assertThat(response.stages()).extracting(DeviceAccessTestResponse.Stage::status)
                .containsExactly("PASSED", "PASSED", "WAITING", "SKIPPED");
    }

    @Test
    void 遥测查询未启用时不得把设备误判为离线() {
        DeviceAccessStatusService service = service(Optional.empty());

        DeviceAccessStatusResponse response = service.find(List.of(deviceId)).getFirst();

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.message()).isEqualTo("ACTIVITY_SOURCE_UNAVAILABLE");
    }

    @Test
    void 遥测查询异常时应降级为未知而不中断查询() {
        when(activityRepository.findByDeviceIds(List.of(deviceId)))
                .thenThrow(new IllegalStateException("遥测库不可用"));
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        DeviceAccessStatusResponse response = service.find(List.of(deviceId)).getFirst();

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.message()).isEqualTo("ACTIVITY_SOURCE_UNAVAILABLE");
    }

    @Test
    void 手动检查时遥测库异常应保留端点和传输结果() {
        when(activityRepository.findByDeviceIds(List.of(deviceId)))
                .thenThrow(new IllegalStateException("遥测库不可用"));
        when(connectionService.test(deviceId)).thenReturn(new DeviceConnectionTestResponse(
                deviceId, "PARTIAL", NOW, 5, "TEST_PARTIAL", List.of()
        ));
        when(transportProbe.probe(deviceId, "DEVICE_TO_PLATFORM_MQTT")).thenReturn(
                new DeviceTransportSessionProbe.Result("PASSED", "TRANSPORT_CONNECTED", NOW.minusSeconds(5))
        );
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        DeviceAccessTestResponse response = service.test(deviceId);

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.stages()).extracting(DeviceAccessTestResponse.Stage::status)
                .containsExactly("PASSED", "PASSED", "UNKNOWN", "UNKNOWN");
    }

    @Test
    void Mqtt会话来源未知时不得被新鲜数据掩盖() {
        when(activityRepository.findByDeviceIds(List.of(deviceId))).thenReturn(Map.of(
                deviceId, new DeviceActivityRepository.ActivityEvidence(
                        NOW.minusSeconds(600), NOW.minusSeconds(30)
                )
        ));
        when(connectionService.test(deviceId)).thenReturn(new DeviceConnectionTestResponse(
                deviceId, "PARTIAL", NOW, 5, "TEST_PARTIAL", List.of()
        ));
        when(transportProbe.probe(deviceId, "DEVICE_TO_PLATFORM_MQTT")).thenReturn(
                new DeviceTransportSessionProbe.Result("UNKNOWN", "TRANSPORT_PROBE_FAILED", null)
        );
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        DeviceAccessTestResponse response = service.test(deviceId);

        assertThat(response.status()).isEqualTo("UNKNOWN");
    }

    @Test
    void Mqtt当前会话未连接时不得直接报在线() {
        when(activityRepository.findByDeviceIds(List.of(deviceId))).thenReturn(Map.of(
                deviceId, new DeviceActivityRepository.ActivityEvidence(
                        NOW.minusSeconds(600), NOW.minusSeconds(30)
                )
        ));
        when(connectionService.test(deviceId)).thenReturn(new DeviceConnectionTestResponse(
                deviceId, "PARTIAL", NOW, 5, "TEST_PARTIAL", List.of()
        ));
        when(transportProbe.probe(deviceId, "DEVICE_TO_PLATFORM_MQTT")).thenReturn(
                new DeviceTransportSessionProbe.Result("WAITING", "TRANSPORT_WAITING", null)
        );
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        DeviceAccessTestResponse response = service.test(deviceId);

        assertThat(response.status()).isEqualTo("WAITING_FOR_DEVICE");
    }

    @Test
    void 未配置时手动检查仍应返回四层结果并记录审计() {
        when(profileMapper.findByDeviceId(deviceId)).thenReturn(Optional.empty());
        DeviceAccessStatusService service = service(Optional.of(activityRepository));

        DeviceAccessTestResponse response = service.test(deviceId);

        assertThat(response.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(response.stages()).extracting(DeviceAccessTestResponse.Stage::layer)
                .containsExactly("ENDPOINT", "TRANSPORT", "ACTIVITY", "DATA");
        assertThat(response.stages()).extracting(DeviceAccessTestResponse.Stage::status)
                .containsExactly("WAITING", "SKIPPED", "SKIPPED", "SKIPPED");
        verify(auditService).recordSuccess(any(), any(), any(), any(), any(), any(), any());
    }

    private DeviceAccessStatusService service(Optional<DeviceActivityRepository> repository) {
        return new DeviceAccessStatusService(
                deviceMapper, profileMapper, repository, transportProbe, connectionService,
                auditService, policyProvider, Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private Device device() {
        return device(deviceId);
    }

    private Device device(UUID id) {
        return new Device(
                id, UUID.randomUUID(), "truck-001", "一号矿卡", "ENABLED",
                null, NOW.minusSeconds(3600), NOW.minusSeconds(3600), 0
        );
    }

    private DeviceConnectionProfile profile() {
        return profile(deviceId);
    }

    private DeviceConnectionProfile profile(UUID id) {
        return new DeviceConnectionProfile(
                UUID.randomUUID(), id, "DEVICE_TO_PLATFORM_MQTT", "mqtt.mine.local", 8883,
                true, "mqtt.mine.local", 5000, 60, 180,
                "PARTIAL", NOW.minusSeconds(120), 10L, "TEST_PARTIAL", "[]",
                NOW.minusSeconds(3600), NOW.minusSeconds(120), 1
        );
    }
}
