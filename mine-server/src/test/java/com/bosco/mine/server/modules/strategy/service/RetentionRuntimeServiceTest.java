package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.modules.strategy.repository.RetentionRuntimeRepository;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 校验保留影响预览的策略复用、作用域边界和遥测查询失败收敛。
 */
class RetentionRuntimeServiceTest {

    private static final UUID PRODUCT_ID =
            UUID.fromString("019f6e20-0000-7000-8000-000000000501");
    private static final UUID GROUP_ID =
            UUID.fromString("019f6e20-0000-7000-8000-000000000502");
    private static final UUID DEVICE_ID =
            UUID.fromString("019f6e20-0000-7000-8000-000000000503");
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-17T08:30:00Z");

    private RetentionRuntimeRepository repository;
    private StrategyConfigurationMapper strategyMapper;
    private StrategyScopeMapper scopeMapper;
    private RetentionRuntimeService service;

    @BeforeEach
    void 创建服务() {
        repository = mock(RetentionRuntimeRepository.class);
        strategyMapper = mock(StrategyConfigurationMapper.class);
        scopeMapper = mock(StrategyScopeMapper.class);
        service = new RetentionRuntimeService(repository, strategyMapper, scopeMapper);
    }

    @Test
    void 应复用保留策略白名单并预览产品全部未归档设备() {
        List<UUID> deviceIds = List.of(DEVICE_ID, UUID.randomUUID());
        when(scopeMapper.isActiveProduct(PRODUCT_ID)).thenReturn(true);
        when(strategyMapper.findUnarchivedDeviceIds(PRODUCT_ID)).thenReturn(deviceIds);
        when(repository.preview(deviceIds, 90, 180, 1_000_000L)).thenReturn(
                new RetentionRuntimeRepository.PreviewSnapshot(
                        OBSERVED_AT,
                        new RetentionRuntimeRepository.DatasetImpact(1_000_000L,
                                Instant.parse("2025-01-01T00:00:00Z"), true),
                        new RetentionRuntimeRepository.DatasetImpact(27L,
                                Instant.parse("2025-02-01T00:00:00Z"), false)
                )
        );

        RetentionRuntimeService.RetentionPreviewView preview = service.preview(
                new RetentionRuntimeService.PreviewCommand(
                        PRODUCT_ID, StrategyScopeType.PRODUCT, PRODUCT_ID, policy()
                )
        );

        assertThat(preview.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(preview.affectedDeviceCount()).isEqualTo(2);
        assertThat(preview.policy()).isEqualTo(
                new RetentionRuntimeService.PolicyView(true, "STANDARD", 90, 180));
        assertThat(preview.rawTelemetry().eligibleRows()).isEqualTo(1_000_000L);
        assertThat(preview.rawTelemetry().atLeast()).isTrue();
        assertThat(preview.qualityEvidence().eligibleRows()).isEqualTo(27L);
        verify(repository).preview(deviceIds, 90, 180, 1_000_000L);
    }

    @Test
    void 应只预览同产品活动设备组中的未归档成员() {
        UUID archivedDeviceId = UUID.randomUUID();
        when(scopeMapper.findGroupById(GROUP_ID)).thenReturn(java.util.Optional.of(
                new StrategyScopeMapper.DeviceGroupRecord(
                        GROUP_ID, PRODUCT_ID, "haul", "运输组", 800, "ACTIVE", null,
                        null, OBSERVED_AT, OBSERVED_AT, 0, 2
                )
        ));
        when(scopeMapper.findActiveMembers(GROUP_ID)).thenReturn(List.of(
                new StrategyScopeMapper.DeviceGroupMemberRecord(
                        DEVICE_ID, "TRUCK-001", "矿卡一号", "ENABLED", OBSERVED_AT),
                new StrategyScopeMapper.DeviceGroupMemberRecord(
                        archivedDeviceId, "TRUCK-002", "矿卡二号", "ARCHIVED", OBSERVED_AT)
        ));
        when(repository.preview(List.of(DEVICE_ID), 90, 180, 1_000_000L)).thenReturn(
                emptySnapshot());

        RetentionRuntimeService.RetentionPreviewView preview = service.preview(
                new RetentionRuntimeService.PreviewCommand(
                        PRODUCT_ID, StrategyScopeType.GROUP, GROUP_ID, policy()
                )
        );

        assertThat(preview.affectedDeviceCount()).isOne();
        verify(repository).preview(List.of(DEVICE_ID), 90, 180, 1_000_000L);
    }

    @Test
    void 应拒绝跨产品归档目标和不合法保留策略() {
        when(strategyMapper.findDeviceContext(DEVICE_ID)).thenReturn(java.util.Optional.of(
                new StrategyConfigurationMapper.DeviceContextRecord(
                        DEVICE_ID, UUID.randomUUID(), "ENABLED")
        ));

        assertThatThrownBy(() -> service.preview(new RetentionRuntimeService.PreviewCommand(
                PRODUCT_ID, StrategyScopeType.DEVICE, DEVICE_ID, policy()
        ))).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("RETENTION-422-002"));

        assertThatThrownBy(() -> service.preview(new RetentionRuntimeService.PreviewCommand(
                PRODUCT_ID, StrategyScopeType.PRODUCT, PRODUCT_ID,
                Map.of(
                        "enabled", true,
                        "retentionClass", "SHORT",
                        "rawTelemetryDays", 90,
                        "qualityEvidenceDays", 180
                )
        ))).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("RETENTION-422-001");
            assertThat(exception.details()).containsKey("violations");
        });
        verifyNoInteractions(repository);
    }

    @Test
    void 遥测查询不可用时不得返回零值伪装成功() {
        when(scopeMapper.isActiveProduct(PRODUCT_ID)).thenReturn(true);
        when(strategyMapper.findUnarchivedDeviceIds(PRODUCT_ID)).thenReturn(List.of(DEVICE_ID));
        when(repository.preview(List.of(DEVICE_ID), 90, 180, 1_000_000L))
                .thenThrow(new IllegalStateException("password=secret unavailable"));

        assertThatThrownBy(() -> service.preview(new RetentionRuntimeService.PreviewCommand(
                PRODUCT_ID, StrategyScopeType.PRODUCT, PRODUCT_ID, policy()
        ))).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("RETENTION-503-001");
            assertThat(exception.details()).isEmpty();
            assertThat(exception).hasMessageNotContaining("secret");
        });
    }

    @Test
    void 应查询有界最近执行事实并收敛查询错误() {
        RetentionRuntimeRepository.ExecutionRecord record =
                new RetentionRuntimeRepository.ExecutionRecord(
                        UUID.randomUUID(), "telemetry-a", "FAILED", 3,
                        100, 20, 2, OBSERVED_AT.minusSeconds(10), OBSERVED_AT,
                        "IllegalStateException"
                );
        when(repository.findExecutions(20)).thenReturn(List.of(record));

        List<RetentionRuntimeService.ExecutionView> executions = service.recentExecutions(20);

        assertThat(executions).singleElement().satisfies(execution -> {
            assertThat(execution.status()).isEqualTo("FAILED");
            assertThat(execution.lastError()).isEqualTo("IllegalStateException");
        });
        assertThatThrownBy(() -> service.recentExecutions(101))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("RETENTION-422-003"));

        when(repository.findExecutions(10)).thenThrow(new IllegalStateException("down"));
        assertThatThrownBy(() -> service.recentExecutions(10))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("RETENTION-503-001"));
    }

    private Map<String, Object> policy() {
        return Map.of(
                "enabled", true,
                "retentionClass", "standard",
                "rawTelemetryDays", 90,
                "qualityEvidenceDays", 180
        );
    }

    private RetentionRuntimeRepository.PreviewSnapshot emptySnapshot() {
        RetentionRuntimeRepository.DatasetImpact empty =
                new RetentionRuntimeRepository.DatasetImpact(0, null, false);
        return new RetentionRuntimeRepository.PreviewSnapshot(OBSERVED_AT, empty, empty);
    }
}
