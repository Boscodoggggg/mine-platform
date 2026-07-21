package com.bosco.mine.server.modules.strategy.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.modules.strategy.repository.RetentionRuntimeRepository;
import com.bosco.mine.server.modules.strategy.repository.RetentionPreviewEvidenceMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.any;
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
    private static final UUID VERSION_ID =
            UUID.fromString("019f6e20-0000-7000-8000-000000000504");
    private static final UUID OPERATOR_ID =
            UUID.fromString("019f6e20-0000-7000-8000-000000000505");
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-17T08:30:00Z");

    private RetentionRuntimeRepository repository;
    private StrategyConfigurationMapper strategyMapper;
    private StrategyScopeMapper scopeMapper;
    private RetentionPreviewEvidenceMapper evidenceMapper;
    private RetentionRuntimeService service;

    @BeforeEach
    void 创建服务() {
        repository = mock(RetentionRuntimeRepository.class);
        strategyMapper = mock(StrategyConfigurationMapper.class);
        scopeMapper = mock(StrategyScopeMapper.class);
        evidenceMapper = mock(RetentionPreviewEvidenceMapper.class);
        service = new RetentionRuntimeService(
                repository, strategyMapper, scopeMapper, evidenceMapper,
                JsonMapper.builder().findAndAddModules().build());
        when(strategyMapper.findVersionById(VERSION_ID)).thenReturn(java.util.Optional.of(
                retentionVersion(true, 90, 180)
        ));
    }

    @Test
    void 应读取已保存版本并按发布后的每设备最终策略预览() {
        UUID overriddenDeviceId = UUID.randomUUID();
        List<UUID> deviceIds = List.of(DEVICE_ID, overriddenDeviceId);
        when(scopeMapper.isActiveProduct(PRODUCT_ID)).thenReturn(true);
        when(strategyMapper.findUnarchivedDeviceIds(PRODUCT_ID)).thenReturn(deviceIds);
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", deviceIds
        )).thenReturn(List.of(new StrategyConfigurationMapper.StrategyFragmentRecord(
                overriddenDeviceId, UUID.randomUUID(), "DEVICE", overriddenDeviceId, null,
                retentionValuesJson(true, 30, 60)
        )));
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> finalPolicies = List.of(
                new RetentionRuntimeRepository.DeviceRetentionPolicy(DEVICE_ID, true, 90, 180),
                new RetentionRuntimeRepository.DeviceRetentionPolicy(overriddenDeviceId, true, 30, 60)
        );
        when(repository.preview(finalPolicies, 1_000_000L)).thenReturn(
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
                        PRODUCT_ID, VERSION_ID, StrategyScopeType.PRODUCT, PRODUCT_ID
                )
        );

        assertThat(preview.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(preview.affectedDeviceCount()).isOne();
        assertThat(preview.targetDeviceCount()).isEqualTo(2);
        assertThat(preview.enabledDeviceCount()).isEqualTo(2);
        assertThat(preview.previewToken()).isNotNull();
        assertThat(preview.expiresAt()).isAfter(preview.observedAt());
        assertThat(preview.policy()).isEqualTo(
                new RetentionRuntimeService.PolicyView(true, "STANDARD", 90, 180));
        assertThat(preview.rawTelemetry().eligibleRows()).isEqualTo(1_000_000L);
        assertThat(preview.rawTelemetry().atLeast()).isTrue();
        assertThat(preview.qualityEvidence().eligibleRows()).isEqualTo(27L);
        verify(repository).preview(finalPolicies, 1_000_000L);
        verify(evidenceMapper).insert(any());
    }

    @Test
    void 最终策略全部禁用时不得依赖遥测库且应签发可停删凭证() {
        when(strategyMapper.findVersionById(VERSION_ID)).thenReturn(java.util.Optional.of(
                retentionVersion(false, 90, 180)
        ));
        when(scopeMapper.isActiveProduct(PRODUCT_ID)).thenReturn(true);
        when(strategyMapper.findUnarchivedDeviceIds(PRODUCT_ID)).thenReturn(List.of(DEVICE_ID));
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of());

        RetentionRuntimeService.RetentionPreviewView preview = service.preview(
                new RetentionRuntimeService.PreviewCommand(
                        PRODUCT_ID, VERSION_ID, StrategyScopeType.PRODUCT, PRODUCT_ID
                )
        );

        assertThat(preview.enabledDeviceCount()).isZero();
        assertThat(preview.rawTelemetry().eligibleRows()).isZero();
        assertThat(preview.qualityEvidence().eligibleRows()).isZero();
        assertThat(preview.previewToken()).isNotNull();
        verifyNoInteractions(repository);
        verify(evidenceMapper).insert(any());
    }

    @Test
    void 激活凭证必须拒绝不同策略内容并且只能绑定一次发布() {
        Instant now = Instant.now();
        when(scopeMapper.isActiveProduct(PRODUCT_ID)).thenReturn(true);
        when(strategyMapper.findUnarchivedDeviceIds(PRODUCT_ID)).thenReturn(List.of(DEVICE_ID));
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of());
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> policies = List.of(
                new RetentionRuntimeRepository.DeviceRetentionPolicy(DEVICE_ID, true, 90, 180)
        );
        RetentionRuntimeRepository.DatasetImpact empty =
                new RetentionRuntimeRepository.DatasetImpact(0, null, false);
        when(repository.preview(policies, 1_000_000L)).thenReturn(
                new RetentionRuntimeRepository.PreviewSnapshot(now, empty, empty));

        RetentionRuntimeService.RetentionPreviewView preview = service.preview(
                new RetentionRuntimeService.PreviewCommand(
                        PRODUCT_ID, VERSION_ID, StrategyScopeType.PRODUCT, PRODUCT_ID));
        ArgumentCaptor<RetentionPreviewEvidenceMapper.PreviewEvidence> captor =
                ArgumentCaptor.forClass(RetentionPreviewEvidenceMapper.PreviewEvidence.class);
        verify(evidenceMapper).insert(captor.capture());
        when(evidenceMapper.findForUpdate(preview.previewToken()))
                .thenReturn(java.util.Optional.of(captor.getValue()));
        when(evidenceMapper.markActivated(preview.previewToken(), DEVICE_ID)).thenReturn(1);

        RetentionActivationGuard.ActivationCommand exact = new RetentionActivationGuard.ActivationCommand(
                preview.previewToken(), VERSION_ID, PRODUCT_ID, StrategyScopeType.PRODUCT,
                PRODUCT_ID, null, readPolicyValues(true, 90, 180), List.of(DEVICE_ID), null);
        service.verify(exact);
        service.markActivated(preview.previewToken(), DEVICE_ID);

        RetentionActivationGuard.ActivationCommand changed = new RetentionActivationGuard.ActivationCommand(
                preview.previewToken(), VERSION_ID, PRODUCT_ID, StrategyScopeType.PRODUCT,
                PRODUCT_ID, null, readPolicyValues(true, 120, 180), List.of(DEVICE_ID), null);
        assertThatThrownBy(() -> service.verify(changed))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("RETENTION-409-001"));
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
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> finalPolicies = List.of(
                new RetentionRuntimeRepository.DeviceRetentionPolicy(DEVICE_ID, true, 90, 180)
        );
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of());
        when(repository.preview(finalPolicies, 1_000_000L)).thenReturn(
                emptySnapshot());

        RetentionRuntimeService.RetentionPreviewView preview = service.preview(
                new RetentionRuntimeService.PreviewCommand(
                        PRODUCT_ID, VERSION_ID, StrategyScopeType.GROUP, GROUP_ID
                )
        );

        assertThat(preview.affectedDeviceCount()).isOne();
        verify(repository).preview(finalPolicies, 1_000_000L);
    }

    @Test
    void 新增组成员应按拟变更后的最终保留策略签发一次性凭证() {
        long groupVersion = 2;
        UUID groupReleaseId = UUID.randomUUID();
        UUID productReleaseId = UUID.randomUUID();
        when(scopeMapper.findGroupById(GROUP_ID)).thenReturn(java.util.Optional.of(
                new StrategyScopeMapper.DeviceGroupRecord(
                        GROUP_ID, PRODUCT_ID, "haul", "运输组", 800, "ACTIVE", null,
                        null, OBSERVED_AT, OBSERVED_AT, groupVersion, 0
                )
        ));
        when(scopeMapper.findActiveMemberIds(GROUP_ID)).thenReturn(List.of());
        when(scopeMapper.countEligibleDevices(PRODUCT_ID, List.of(DEVICE_ID))).thenReturn(1L);
        when(strategyMapper.findCurrentGroupReleases(GROUP_ID)).thenReturn(List.of(
                retentionRelease(groupReleaseId, true, 30, 60)
        ));
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of(new StrategyConfigurationMapper.StrategyFragmentRecord(
                DEVICE_ID, productReleaseId, "PRODUCT", PRODUCT_ID, null,
                "{\"policy\":{\"enabled\":true,\"retentionClass\":\"LONG\","
                        + "\"rawTelemetryDays\":365,\"qualityEvidenceDays\":365}}"
        )));
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> policies = List.of(
                new RetentionRuntimeRepository.DeviceRetentionPolicy(DEVICE_ID, true, 30, 60)
        );
        when(repository.preview(policies, 1_000_000L)).thenReturn(emptySnapshot());

        RetentionRuntimeService.GroupChangePreviewView result = service.previewGroupChange(
                new RetentionRuntimeService.GroupChangePreviewCommand(
                        GROUP_ID, RetentionGroupChangeGuard.ChangeType.MEMBERSHIP,
                        groupVersion, List.of(DEVICE_ID), null
                )
        );

        assertThat(result.required()).isTrue();
        assertThat(result.preview()).isNotNull();
        assertThat(result.preview().targetDeviceCount()).isOne();
        assertThat(result.preview().affectedDeviceCount()).isOne();
        assertThat(result.preview().policy().rawTelemetryDays()).isEqualTo(30);
        verify(repository).preview(policies, 1_000_000L);
        ArgumentCaptor<RetentionPreviewEvidenceMapper.PreviewEvidence> captor =
                ArgumentCaptor.forClass(RetentionPreviewEvidenceMapper.PreviewEvidence.class);
        verify(evidenceMapper).insert(captor.capture());
        assertThat(captor.getValue().activationType()).isEqualTo("GROUP_CHANGE");
        assertThat(captor.getValue().changeType()).isEqualTo("MEMBERSHIP");
        assertThat(captor.getValue().groupVersion()).isEqualTo(groupVersion);
        when(evidenceMapper.findForUpdate(result.preview().previewToken()))
                .thenReturn(java.util.Optional.of(withEvidenceState(
                        captor.getValue(), OPERATOR_ID, Instant.now().plusSeconds(60), null)));
        when(evidenceMapper.markGroupChangeActivated(
                result.preview().previewToken(), GROUP_ID)).thenReturn(1, 0);
        RetentionGroupChangeGuard.ActivationCommand exact =
                new RetentionGroupChangeGuard.ActivationCommand(
                        result.preview().previewToken(),
                        RetentionGroupChangeGuard.ChangeType.MEMBERSHIP,
                        GROUP_ID, PRODUCT_ID, groupVersion, 800, 800,
                        List.of(DEVICE_ID), List.of(), List.of(DEVICE_ID), OPERATOR_ID);

        try (var stpUtil = mockStatic(StpUtil.class)) {
            assertThat(service.verify(exact)).isTrue();
            stpUtil.verify(() -> StpUtil.checkPermission("device:strategy:publish"));
        }
        try (var stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(() -> StpUtil.checkPermission("device:strategy:publish"))
                    .thenThrow(new IllegalStateException("permission-revoked"));
            assertThatThrownBy(() -> service.verify(exact))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("permission-revoked");
        }
        service.markGroupChangeActivated(result.preview().previewToken(), GROUP_ID);
        assertThatThrownBy(() -> service.markGroupChangeActivated(
                result.preview().previewToken(), GROUP_ID))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("RETENTION-409-001"));

        RetentionGroupChangeGuard.ActivationCommand changed =
                new RetentionGroupChangeGuard.ActivationCommand(
                        result.preview().previewToken(),
                        RetentionGroupChangeGuard.ChangeType.MEMBERSHIP,
                        GROUP_ID, PRODUCT_ID, groupVersion, 800, 700,
                        List.of(DEVICE_ID), List.of(), List.of(DEVICE_ID), OPERATOR_ID);
        try (var ignored = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> service.verify(changed))
                    .isInstanceOfSatisfying(MineException.class, exception ->
                            assertThat(exception.errorCode().code()).isEqualTo("RETENTION-409-001"));
        }
        when(evidenceMapper.findForUpdate(result.preview().previewToken()))
                .thenReturn(java.util.Optional.of(withEvidenceState(
                        captor.getValue(), OPERATOR_ID, Instant.now().minusSeconds(1), null)));
        try (var ignored = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> service.verify(exact))
                    .isInstanceOfSatisfying(MineException.class, exception ->
                            assertThat(exception.errorCode().code()).isEqualTo("RETENTION-409-001"));
        }
        when(evidenceMapper.findForUpdate(result.preview().previewToken()))
                .thenReturn(java.util.Optional.of(withEvidenceState(
                        captor.getValue(), OPERATOR_ID, Instant.now().plusSeconds(60), Instant.now())));
        try (var ignored = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> service.verify(exact))
                    .isInstanceOfSatisfying(MineException.class, exception ->
                            assertThat(exception.errorCode().code()).isEqualTo("RETENTION-409-001"));
        }
    }

    @Test
    void 移除成员应回退到产品保留策略() {
        long groupVersion = 3;
        UUID groupReleaseId = UUID.randomUUID();
        UUID productReleaseId = UUID.randomUUID();
        stubGroup(groupVersion, 800, List.of(DEVICE_ID));
        when(strategyMapper.findCurrentGroupReleases(GROUP_ID)).thenReturn(List.of(
                retentionRelease(groupReleaseId, true, 30, 60)
        ));
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of(
                fragment(DEVICE_ID, groupReleaseId, "GROUP", GROUP_ID, 800, true, 30, 60),
                fragment(DEVICE_ID, productReleaseId, "PRODUCT", PRODUCT_ID, null, true, 90, 180)
        ));
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> policies = List.of(
                new RetentionRuntimeRepository.DeviceRetentionPolicy(DEVICE_ID, true, 90, 180)
        );
        when(repository.preview(policies, 1_000_000L)).thenReturn(emptySnapshot());

        RetentionRuntimeService.GroupChangePreviewView result = service.previewGroupChange(
                new RetentionRuntimeService.GroupChangePreviewCommand(
                        GROUP_ID, RetentionGroupChangeGuard.ChangeType.MEMBERSHIP,
                        groupVersion, List.of(), null));

        assertThat(result.required()).isTrue();
        assertThat(result.preview().affectedDeviceCount()).isOne();
        verify(repository).preview(policies, 1_000_000L);
    }

    @Test
    void 降低优先级应切换到更高优先级设备组策略() {
        long groupVersion = 4;
        UUID groupReleaseId = UUID.randomUUID();
        UUID competingGroupId = UUID.randomUUID();
        UUID competingReleaseId = UUID.randomUUID();
        stubGroup(groupVersion, 800, List.of(DEVICE_ID));
        when(strategyMapper.findCurrentGroupReleases(GROUP_ID)).thenReturn(List.of(
                retentionRelease(groupReleaseId, true, 30, 60)
        ));
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of(
                fragment(DEVICE_ID, groupReleaseId, "GROUP", GROUP_ID, 800, true, 30, 60),
                fragment(DEVICE_ID, competingReleaseId, "GROUP", competingGroupId, 750,
                        true, 90, 180)
        ));
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> policies = List.of(
                new RetentionRuntimeRepository.DeviceRetentionPolicy(DEVICE_ID, true, 90, 180)
        );
        when(repository.preview(policies, 1_000_000L)).thenReturn(emptySnapshot());

        RetentionRuntimeService.GroupChangePreviewView result = service.previewGroupChange(
                new RetentionRuntimeService.GroupChangePreviewCommand(
                        GROUP_ID, RetentionGroupChangeGuard.ChangeType.PRIORITY,
                        groupVersion, List.of(), 700));

        assertThat(result.preview().affectedDeviceCount()).isOne();
        assertThat(result.preview().enabledDeviceCount()).isOne();
        verify(repository).preview(policies, 1_000_000L);
    }

    @Test
    void 归档设备组应移除组策略并回退到产品策略() {
        long groupVersion = 5;
        UUID groupReleaseId = UUID.randomUUID();
        UUID productReleaseId = UUID.randomUUID();
        stubGroup(groupVersion, 800, List.of(DEVICE_ID));
        when(strategyMapper.findCurrentGroupReleases(GROUP_ID)).thenReturn(List.of(
                retentionRelease(groupReleaseId, true, 30, 60)
        ));
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of(
                fragment(DEVICE_ID, groupReleaseId, "GROUP", GROUP_ID, 800, true, 30, 60),
                fragment(DEVICE_ID, productReleaseId, "PRODUCT", PRODUCT_ID, null, true, 90, 180)
        ));
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> policies = List.of(
                new RetentionRuntimeRepository.DeviceRetentionPolicy(DEVICE_ID, true, 90, 180)
        );
        when(repository.preview(policies, 1_000_000L)).thenReturn(emptySnapshot());

        RetentionRuntimeService.GroupChangePreviewView result = service.previewGroupChange(
                new RetentionRuntimeService.GroupChangePreviewCommand(
                        GROUP_ID, RetentionGroupChangeGuard.ChangeType.GROUP_ARCHIVE,
                        groupVersion, List.of(), null));

        assertThat(result.preview().affectedDeviceCount()).isOne();
        verify(repository).preview(policies, 1_000_000L);
    }

    @Test
    void 设备组变化后全部停用删除时不得查询遥测库() {
        long groupVersion = 6;
        UUID groupReleaseId = UUID.randomUUID();
        stubGroup(groupVersion, 800, List.of());
        when(scopeMapper.countEligibleDevices(PRODUCT_ID, List.of(DEVICE_ID))).thenReturn(1L);
        when(strategyMapper.findCurrentGroupReleases(GROUP_ID)).thenReturn(List.of(
                retentionRelease(groupReleaseId, false, 30, 60)
        ));
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of(fragment(
                DEVICE_ID, UUID.randomUUID(), "PRODUCT", PRODUCT_ID, null, true, 90, 180)));

        RetentionRuntimeService.GroupChangePreviewView result = service.previewGroupChange(
                new RetentionRuntimeService.GroupChangePreviewCommand(
                        GROUP_ID, RetentionGroupChangeGuard.ChangeType.MEMBERSHIP,
                        groupVersion, List.of(DEVICE_ID), null));

        assertThat(result.required()).isTrue();
        assertThat(result.preview().enabledDeviceCount()).isZero();
        assertThat(result.preview().rawTelemetry().eligibleRows()).isZero();
        assertThat(result.preview().qualityEvidence().eligibleRows()).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void 应拒绝跨产品归档目标和不合法保留策略() {
        when(strategyMapper.findDeviceContext(DEVICE_ID)).thenReturn(java.util.Optional.of(
                new StrategyConfigurationMapper.DeviceContextRecord(
                        DEVICE_ID, UUID.randomUUID(), "ENABLED")
        ));

        assertThatThrownBy(() -> service.preview(new RetentionRuntimeService.PreviewCommand(
                PRODUCT_ID, VERSION_ID, StrategyScopeType.DEVICE, DEVICE_ID
        ))).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("RETENTION-422-002"));

        when(strategyMapper.findVersionById(VERSION_ID)).thenReturn(java.util.Optional.of(
                new StrategyConfigurationMapper.StrategyVersionRecord(
                        VERSION_ID, PRODUCT_ID, "RETENTION", 2, "DRAFT",
                        "{\"policy\":{\"enabled\":true,\"retentionClass\":\"SHORT\","
                                + "\"rawTelemetryDays\":90,\"qualityEvidenceDays\":180}}",
                        null, "非法策略", null, OBSERVED_AT, OBSERVED_AT, 1
                )
        ));
        when(scopeMapper.isActiveProduct(PRODUCT_ID)).thenReturn(true);
        assertThatThrownBy(() -> service.preview(new RetentionRuntimeService.PreviewCommand(
                PRODUCT_ID, VERSION_ID, StrategyScopeType.PRODUCT, PRODUCT_ID
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
        when(strategyMapper.findCurrentFragmentsForDevices(
                PRODUCT_ID, "RETENTION", List.of(DEVICE_ID)
        )).thenReturn(List.of());
        List<RetentionRuntimeRepository.DeviceRetentionPolicy> finalPolicies = List.of(
                new RetentionRuntimeRepository.DeviceRetentionPolicy(DEVICE_ID, true, 90, 180)
        );
        when(repository.preview(finalPolicies, 1_000_000L))
                .thenThrow(new IllegalStateException("password=secret unavailable"));

        assertThatThrownBy(() -> service.preview(new RetentionRuntimeService.PreviewCommand(
                PRODUCT_ID, VERSION_ID, StrategyScopeType.PRODUCT, PRODUCT_ID
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

    private StrategyConfigurationMapper.StrategyVersionRecord retentionVersion(
            boolean enabled,
            int rawDays,
            int qualityDays
    ) {
        return new StrategyConfigurationMapper.StrategyVersionRecord(
                VERSION_ID, PRODUCT_ID, "RETENTION", 2, "DRAFT",
                retentionValuesJson(enabled, rawDays, qualityDays), null,
                "保留策略", null, OBSERVED_AT, OBSERVED_AT, 1
        );
    }

    private StrategyConfigurationMapper.StrategyReleaseRecord retentionRelease(
            UUID releaseId,
            boolean enabled,
            int rawDays,
            int qualityDays
    ) {
        return new StrategyConfigurationMapper.StrategyReleaseRecord(
                releaseId, PRODUCT_ID, VERSION_ID, "RETENTION", 8,
                "GROUP", GROUP_ID, 800, "PUBLISH", null,
                "组保留策略", "sha256:" + "a".repeat(64),
                retentionValuesJson(enabled, rawDays, qualityDays), OBSERVED_AT
        );
    }

    private String retentionValuesJson(boolean enabled, int rawDays, int qualityDays) {
        String retentionClass = rawDays <= 30 ? "SHORT" : "STANDARD";
        return "{\"policy\":{\"enabled\":" + enabled
                + ",\"retentionClass\":\"" + retentionClass
                + "\",\"rawTelemetryDays\":" + rawDays
                + ",\"qualityEvidenceDays\":" + qualityDays + "}}";
    }

    private Map<String, Object> readPolicyValues(boolean enabled, int rawDays, int qualityDays) {
        return Map.of("policy", Map.of(
                "enabled", enabled,
                "retentionClass", rawDays <= 30 ? "SHORT" : "STANDARD",
                "rawTelemetryDays", rawDays,
                "qualityEvidenceDays", qualityDays
        ));
    }

    private RetentionRuntimeRepository.PreviewSnapshot emptySnapshot() {
        RetentionRuntimeRepository.DatasetImpact empty =
                new RetentionRuntimeRepository.DatasetImpact(0, null, false);
        return new RetentionRuntimeRepository.PreviewSnapshot(OBSERVED_AT, empty, empty);
    }

    private void stubGroup(long version, int priority, List<UUID> memberIds) {
        when(scopeMapper.findGroupById(GROUP_ID)).thenReturn(java.util.Optional.of(
                new StrategyScopeMapper.DeviceGroupRecord(
                        GROUP_ID, PRODUCT_ID, "haul", "运输组", priority, "ACTIVE", null,
                        null, OBSERVED_AT, OBSERVED_AT, version, memberIds.size())
        ));
        when(scopeMapper.findActiveMemberIds(GROUP_ID)).thenReturn(memberIds);
    }

    private StrategyConfigurationMapper.StrategyFragmentRecord fragment(
            UUID deviceId,
            UUID releaseId,
            String scopeType,
            UUID scopeId,
            Integer groupPriority,
            boolean enabled,
            int rawDays,
            int qualityDays
    ) {
        return new StrategyConfigurationMapper.StrategyFragmentRecord(
                deviceId, releaseId, scopeType, scopeId, groupPriority,
                retentionValuesJson(enabled, rawDays, qualityDays));
    }

    private RetentionPreviewEvidenceMapper.PreviewEvidence withEvidenceState(
            RetentionPreviewEvidenceMapper.PreviewEvidence evidence,
            UUID operatorId,
            Instant expiresAt,
            Instant activatedAt
    ) {
        return new RetentionPreviewEvidenceMapper.PreviewEvidence(
                evidence.id(), operatorId, evidence.activationType(),
                evidence.changeType(), evidence.groupVersion(), evidence.strategyVersionId(),
                evidence.productId(), evidence.scopeType(), evidence.scopeId(),
                evidence.groupPriority(), evidence.candidateHash(), evidence.resolutionHash(),
                evidence.observedAt(), expiresAt, evidence.activatedReleaseId(), activatedAt);
    }
}
