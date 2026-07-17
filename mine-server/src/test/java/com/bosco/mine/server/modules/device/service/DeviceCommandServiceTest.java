package com.bosco.mine.server.modules.device.service;

import com.bosco.mine.server.modules.device.dto.DeviceResponse;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.model.Product;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.strategy.service.StrategyRuntimeSnapshotService;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验设备台账变化会同步生成各类运行策略增量快照。
 */
class DeviceCommandServiceTest {

    private DeviceMapper mapper;
    private StrategyRuntimeSnapshotService runtimeSnapshotService;
    private DeviceCommandService service;

    @BeforeEach
    void 创建设备写服务测试环境() {
        mapper = mock(DeviceMapper.class);
        runtimeSnapshotService = mock(StrategyRuntimeSnapshotService.class);
        service = new DeviceCommandService(
                mapper,
                mock(OperationAuditService.class),
                runtimeSnapshotService
        );
    }

    @Test
    void 创建设备应生成保留策略增量快照() {
        UUID productId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        when(mapper.findProductById(productId)).thenReturn(Optional.of(new Product(
                productId, "haul-truck", "矿卡", "HAUL_TRUCK", "ACTIVE",
                null, now, now, 0
        )));
        when(mapper.findDeviceById(any())).thenAnswer(invocation -> {
            UUID deviceId = invocation.getArgument(0);
            return Optional.of(new Device(
                    deviceId, productId, "truck-001", "一号矿卡", "ENABLED",
                    null, now, now, 0
            ));
        });

        DeviceResponse created = service.createDevice(
                productId, "truck-001", "一号矿卡", "ENABLED");

        verify(runtimeSnapshotService).captureDelta(
                productId, "RETENTION", List.of(created.id()),
                StrategyRuntimeSnapshotService.ChangeReason.DEVICE_CREATE
        );
    }

    @Test
    void 归档设备应生成删除本地保留策略的增量快照() {
        UUID productId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        Device device = new Device(
                deviceId, productId, "truck-001", "一号矿卡", "ENABLED",
                null, now, now, 2
        );
        when(mapper.findDeviceById(deviceId)).thenReturn(Optional.of(device));
        when(mapper.archiveDevice(eq(deviceId), eq(2L), any())).thenReturn(1);

        service.archiveDevice(deviceId, 2);

        verify(runtimeSnapshotService).captureDelta(
                productId, "RETENTION", List.of(deviceId),
                StrategyRuntimeSnapshotService.ChangeReason.DEVICE_ARCHIVE
        );
    }
}
