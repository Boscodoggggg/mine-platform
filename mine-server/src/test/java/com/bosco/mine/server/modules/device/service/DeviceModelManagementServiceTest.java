package com.bosco.mine.server.modules.device.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.device.repository.DeviceModelManagementMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验物模型与协议映射发布前的现场安全约束。
 */
class DeviceModelManagementServiceTest {

    private DeviceModelManagementMapper mapper;
    private DeviceModelManagementService service;

    @BeforeEach
    void 创建设备模型服务测试环境() {
        mapper = mock(DeviceModelManagementMapper.class);
        service = new DeviceModelManagementService(mapper, mock(OperationAuditService.class));
    }

    @Test
    void 应拒绝发布没有字段的物模型() {
        UUID versionId = UUID.randomUUID();
        when(mapper.findModelVersion(versionId)).thenReturn(Optional.of(
                new DeviceModelManagementMapper.ModelVersionRecord(
                        versionId, UUID.randomUUID(), 1, "DRAFT", "initial", 0
                )
        ));
        when(mapper.countModelFields(versionId)).thenReturn(0L);

        assertThatThrownBy(() -> service.publishModel(versionId, 0))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode().code())
                                .isEqualTo("DEVICE-MODEL-422-001"));
        verify(mapper, never()).publishModelVersion(versionId, 0, null);
    }

    @Test
    void 应拒绝协议映射指向未发布的物模型字段() {
        UUID mappingId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(mapper.findProtocolVersion(mappingId)).thenReturn(Optional.of(
                new DeviceModelManagementMapper.ProtocolVersionRecord(
                        mappingId, productId, "vendor-a", "Vendor A", "MQTT_JSON",
                        "TELEMETRY", 1, "DRAFT", "devices/+/telemetry", null, 0
                )
        ));
        when(mapper.findProtocolFields(mappingId)).thenReturn(List.of(
                new DeviceModelManagementMapper.ProtocolFieldRecord(
                        UUID.randomUUID(), mappingId, "$.rpm", "INTEGER", "engineRpm",
                        "NONE", null, null, null, true, 10, 0
                )
        ));
        when(mapper.countPublishedModelIdentifiers(productId, List.of("engineRpm"))).thenReturn(0L);

        assertThatThrownBy(() -> service.publishProtocol(mappingId, 0))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode().code())
                                .isEqualTo("DEVICE-MODEL-422-002"));
    }
}
