package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验策略草稿、发布、回滚和有效值解析的事务规则。
 */
class StrategyConfigurationServiceTest {

    private final UUID productId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private StrategyConfigurationMapper mapper;
    private StrategyScopeMapper scopeMapper;
    private OperationAuditService auditService;
    private EffectiveStrategyCacheInvalidator cacheInvalidator;
    private StrategyRuntimeSnapshotService runtimeSnapshotService;
    private StrategyConfigurationService service;

    @BeforeEach
    void 创建策略配置服务测试环境() {
        mapper = mock(StrategyConfigurationMapper.class);
        scopeMapper = mock(StrategyScopeMapper.class);
        auditService = mock(OperationAuditService.class);
        cacheInvalidator = mock(EffectiveStrategyCacheInvalidator.class);
        runtimeSnapshotService = mock(StrategyRuntimeSnapshotService.class);
        service = new StrategyConfigurationService(
                mapper,
                scopeMapper,
                auditService,
                JsonMapper.builder().findAndAddModules().build(),
                cacheInvalidator,
                runtimeSnapshotService
        );
    }

    @Test
    void 创建草稿应执行白名单校验并分配产品内单调版本号() {
        when(scopeMapper.isActiveProduct(productId)).thenReturn(true);
        when(mapper.nextVersionNumber(productId, "ONLINE_STATUS")).thenReturn(3L);

        StrategyConfigurationService.StrategyVersionView created = service.createDraft(
                new StrategyConfigurationService.CreateDraftCommand(
                        productId,
                        "online_status",
                        Map.of("offlineThresholdSeconds", 300L),
                        "运输车辆离线规则"
                )
        );

        assertThat(created.strategyType()).isEqualTo("ONLINE_STATUS");
        assertThat(created.versionNumber()).isEqualTo(3);
        assertThat(created.values()).containsEntry("offlineThresholdSeconds", 300);
        verify(mapper).lockProduct(productId);
        verify(mapper).insertVersion(
                eq(created.id()), eq(productId), eq("ONLINE_STATUS"), eq(3L),
                eq("{\"offlineThresholdSeconds\":300}"), eq("运输车辆离线规则"), eq(null)
        );
        verify(auditService).recordSuccess(
                eq("DEVICE_STRATEGY"), eq("CREATE_STRATEGY_DRAFT"), eq("device:strategy:edit"),
                eq("STRATEGY_VERSION"), eq(created.id()), any(), any()
        );
    }

    @Test
    void 应创建经过白名单规范化的数据质量策略草稿() {
        when(scopeMapper.isActiveProduct(productId)).thenReturn(true);
        when(mapper.nextVersionNumber(productId, "DATA_QUALITY")).thenReturn(1L);

        StrategyConfigurationService.StrategyVersionView created = service.createDraft(
                new StrategyConfigurationService.CreateDraftCommand(
                        productId,
                        "data_quality",
                        Map.of(
                                "staleAfterSeconds", 300L,
                                "required.engine.rpm", true,
                                "minimum.engine.rpm", 10.5D
                        ),
                        "矿卡转速质量规则"
                )
        );

        assertThat(created.strategyType()).isEqualTo("DATA_QUALITY");
        assertThat(created.values())
                .containsEntry("staleAfterSeconds", 300)
                .containsEntry("required.engine.rpm", true)
                .containsEntry("minimum.engine.rpm", new BigDecimal("10.5"));
        verify(mapper).insertVersion(
                eq(created.id()), eq(productId), eq("DATA_QUALITY"), eq(1L),
                eq("{\"minimum.engine.rpm\":10.5,\"required.engine.rpm\":true,\"staleAfterSeconds\":300}"),
                eq("矿卡转速质量规则"), eq(null)
        );
    }

    @Test
    void 应创建整组覆盖且经过白名单规范化的告警策略草稿() {
        when(scopeMapper.isActiveProduct(productId)).thenReturn(true);
        when(mapper.nextVersionNumber(productId, "ALARM")).thenReturn(1L);
        Map<String, Object> rule = new java.util.LinkedHashMap<>();
        rule.put("key", "engine-temperature-high");
        rule.put("name", "发动机高温");
        rule.put("sourceType", "numeric");
        rule.put("severity", "critical");
        rule.put("triggerDurationSeconds", 30L);
        rule.put("recoverDurationSeconds", 60);
        rule.put("cooldownSeconds", 300);
        rule.put("propertyId", "engine.temperature");
        rule.put("operator", "gt");
        rule.put("threshold", 95.0D);
        rule.put("recoverThreshold", 90L);

        StrategyConfigurationService.StrategyVersionView created = service.createDraft(
                new StrategyConfigurationService.CreateDraftCommand(
                        productId,
                        "alarm",
                        Map.of("rules", List.of(rule)),
                        "矿卡发动机告警规则"
                )
        );

        assertThat(created.strategyType()).isEqualTo("ALARM");
        assertThat(created.values()).containsOnlyKeys("rules");
        assertThat(created.values().get("rules").toString())
                .contains("engine-temperature-high", "NUMERIC", "CRITICAL", "9E+1");
        verify(mapper).insertVersion(
                eq(created.id()), eq(productId), eq("ALARM"), eq(1L),
                any(), eq("矿卡发动机告警规则"), eq(null)
        );
    }

    @Test
    void 应创建原子覆盖且经过白名单规范化的数据保留策略草稿() {
        when(scopeMapper.isActiveProduct(productId)).thenReturn(true);
        when(mapper.nextVersionNumber(productId, "RETENTION")).thenReturn(1L);

        StrategyConfigurationService.StrategyVersionView created = service.createDraft(
                new StrategyConfigurationService.CreateDraftCommand(
                        productId,
                        "retention",
                        Map.of("policy", Map.of(
                                "enabled", true,
                                "retentionClass", "standard",
                                "rawTelemetryDays", 90L,
                                "qualityEvidenceDays", 180
                        )),
                        "矿卡标准保留规则"
                )
        );

        assertThat(created.strategyType()).isEqualTo("RETENTION");
        assertThat(created.values()).containsOnlyKeys("policy");
        assertThat(created.values().get("policy").toString())
                .contains("STANDARD", "rawTelemetryDays=90", "qualityEvidenceDays=180");
        verify(mapper).insertVersion(
                eq(created.id()), eq(productId), eq("RETENTION"), eq(1L),
                eq("{\"policy\":{\"enabled\":true,\"qualityEvidenceDays\":180,"
                        + "\"rawTelemetryDays\":90,\"retentionClass\":\"STANDARD\"}}"),
                eq("矿卡标准保留规则"), eq(null)
        );
    }

    @Test
    void 任意字段和非整数值必须被类型注册表拒绝() {
        when(scopeMapper.isActiveProduct(productId)).thenReturn(true);

        assertThatThrownBy(() -> service.createDraft(
                new StrategyConfigurationService.CreateDraftCommand(
                        productId,
                        "ONLINE_STATUS",
                        Map.of("script", "return true", "offlineThresholdSeconds", "300"),
                        null
                )
        )).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-422-004");
            assertThat(exception.details()).containsKey("violations");
        });

        verify(mapper, never()).insertVersion(any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void 修改草稿时乐观锁冲突应返回稳定错误() {
        when(mapper.findVersionById(versionId)).thenReturn(Optional.of(version("DRAFT", 4, 120)));
        when(mapper.updateDraft(
                versionId, "{\"offlineThresholdSeconds\":300}", "新阈值", 4, null
        )).thenReturn(0);

        assertThatThrownBy(() -> service.updateDraft(
                versionId,
                new StrategyConfigurationService.UpdateDraftCommand(
                        Map.of("offlineThresholdSeconds", 300), "新阈值", 4
                )
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-409-005"));
    }

    @Test
    void 应能按资源地址查询单个策略版本和发布() {
        UUID releaseId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(mapper.findVersionById(versionId)).thenReturn(Optional.of(version("PUBLISHED", 5, 120)));
        when(mapper.findRelease(releaseId)).thenReturn(Optional.of(
                release(releaseId, groupId, 800, 7, "{\"offlineThresholdSeconds\":120}")
        ));

        assertThat(service.findVersion(versionId)).satisfies(version -> {
            assertThat(version.id()).isEqualTo(versionId);
            assertThat(version.status()).isEqualTo("PUBLISHED");
        });
        assertThat(service.findRelease(releaseId)).satisfies(release -> {
            assertThat(release.id()).isEqualTo(releaseId);
            assertThat(release.scopeId()).isEqualTo(groupId);
        });
    }

    @Test
    void 同优先级组同字段异值必须阻止整个发布事务() {
        UUID groupId = UUID.randomUUID();
        UUID otherGroupId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        stubDraftForPublish(300);
        when(scopeMapper.findGroupByIdForUpdate(groupId)).thenReturn(Optional.of(
                group(groupId, 800)
        ));
        when(scopeMapper.findActiveMemberIds(groupId)).thenReturn(List.of(deviceId));
        when(mapper.findCurrentFragmentsForDevices(
                productId, "ONLINE_STATUS", List.of(deviceId)
        )).thenReturn(List.of(new StrategyConfigurationMapper.StrategyFragmentRecord(
                deviceId,
                UUID.randomUUID(),
                "GROUP",
                otherGroupId,
                800,
                "{\"offlineThresholdSeconds\":120}"
        )));

        assertThatThrownBy(() -> service.publish(new StrategyConfigurationService.PublishCommand(
                versionId, StrategyScopeType.GROUP, groupId, "提高弱网容忍度", "strategy-publish-001"
        ))).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-409-001");
            assertThat(exception.details()).containsEntry("conflictCount", 1L);
        });

        verify(mapper, never()).publishVersion(any(), any(), anyLong(), any());
        verify(mapper, never()).insertRelease(
                any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void 同优先级相同值应合并并原子写入不可变发布() {
        UUID groupId = UUID.randomUUID();
        UUID otherGroupId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        stubDraftForPublish(300);
        when(scopeMapper.findGroupByIdForUpdate(groupId)).thenReturn(Optional.of(group(groupId, 800)));
        when(scopeMapper.findActiveMemberIds(groupId)).thenReturn(List.of(deviceId));
        when(mapper.findCurrentFragmentsForDevices(
                productId, "ONLINE_STATUS", List.of(deviceId)
        )).thenReturn(List.of(new StrategyConfigurationMapper.StrategyFragmentRecord(
                deviceId,
                UUID.randomUUID(),
                "GROUP",
                otherGroupId,
                800,
                "{\"offlineThresholdSeconds\":300}"
        )));
        when(mapper.publishVersion(eq(versionId), any(), eq(4L), eq(null))).thenReturn(1);
        when(mapper.nextReleaseSequence(productId)).thenReturn(7L);

        StrategyConfigurationService.StrategyReleaseView release = service.publish(
                new StrategyConfigurationService.PublishCommand(
                        versionId, StrategyScopeType.GROUP, groupId,
                        "提高弱网容忍度", "strategy-publish-001"
                )
        );

        assertThat(release.releaseSequence()).isEqualTo(7);
        assertThat(release.scopeType()).isEqualTo(StrategyScopeType.GROUP);
        assertThat(release.groupPriority()).isEqualTo(800);
        assertThat(release.contentHash()).startsWith("sha256:");
        verify(mapper).insertRelease(
                eq(release.id()), eq(productId), eq(versionId), eq("ONLINE_STATUS"), eq(7L),
                eq("GROUP"), eq(groupId), eq(800), eq("PUBLISH"), eq(null),
                eq("提高弱网容忍度"), eq("strategy-publish-001"), eq(null)
        );
        verify(cacheInvalidator).invalidateAfterCommit(
                "ONLINE_STATUS",
                List.of(deviceId),
                EffectiveStrategyCacheInvalidator.InvalidationReason.PUBLISH
        );
        verify(runtimeSnapshotService).captureDelta(
                productId, "ONLINE_STATUS", List.of(deviceId),
                StrategyRuntimeSnapshotService.ChangeReason.PUBLISH
        );
    }

    @Test
    void 继承后的数据质量下限大于上限时必须阻止发布() {
        UUID groupId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        StrategyConfigurationMapper.StrategyVersionRecord draft = dataQualityVersion(
                "DRAFT", "{\"minimum.engine.rpm\":200}", 0
        );
        when(mapper.findReleaseByIdempotency(null, "quality-publish-001"))
                .thenReturn(Optional.empty());
        when(mapper.findVersionById(versionId)).thenReturn(Optional.of(draft));
        when(mapper.findVersionForUpdate(versionId)).thenReturn(Optional.of(draft));
        when(scopeMapper.findGroupByIdForUpdate(groupId))
                .thenReturn(Optional.of(group(groupId, 800)));
        when(scopeMapper.findActiveMemberIds(groupId)).thenReturn(List.of(deviceId));
        when(mapper.findCurrentFragmentsForDevices(
                productId, "DATA_QUALITY", List.of(deviceId)
        )).thenReturn(List.of(new StrategyConfigurationMapper.StrategyFragmentRecord(
                deviceId, UUID.randomUUID(), "PRODUCT", productId, null,
                "{\"maximum.engine.rpm\":100}"
        )));

        assertThatThrownBy(() -> service.publish(
                new StrategyConfigurationService.PublishCommand(
                        versionId, StrategyScopeType.GROUP, groupId,
                        "提高转速下限", "quality-publish-001"
                )
        )).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-422-004");
            assertThat(exception.details()).containsEntry("deviceId", deviceId)
                    .containsKey("violations");
        });

        verify(mapper, never()).publishVersion(any(), any(), anyLong(), any());
    }

    @Test
    void 相同幂等键和相同发布语义应直接返回已有发布() {
        UUID groupId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        StrategyConfigurationMapper.StrategyReleaseRecord existing = release(
                releaseId, groupId, 800, 7, "{\"offlineThresholdSeconds\":300}"
        );
        when(mapper.findReleaseByIdempotency(null, "strategy-publish-001"))
                .thenReturn(Optional.of(existing));

        StrategyConfigurationService.StrategyReleaseView release = service.publish(
                new StrategyConfigurationService.PublishCommand(
                        versionId, StrategyScopeType.GROUP, groupId,
                        "历史发布", "strategy-publish-001"
                )
        );

        assertThat(release.id()).isEqualTo(releaseId);
        verify(mapper).lockIdempotencyKey(null, "strategy-publish-001");
        verify(mapper, never()).findVersionById(any());
        verify(mapper, never()).insertRelease(
                any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void 相同幂等键复用于不同发布请求必须返回稳定冲突() {
        UUID existingGroupId = UUID.randomUUID();
        UUID requestedGroupId = UUID.randomUUID();
        StrategyConfigurationMapper.StrategyReleaseRecord existing = release(
                UUID.randomUUID(), existingGroupId, 800, 7,
                "{\"offlineThresholdSeconds\":300}"
        );
        when(mapper.findReleaseByIdempotency(null, "strategy-publish-001"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.publish(
                new StrategyConfigurationService.PublishCommand(
                        versionId, StrategyScopeType.GROUP, requestedGroupId,
                        "历史发布", "strategy-publish-001"
                )
        )).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-409-006");
            assertThat(exception.details()).containsEntry("releaseId", existing.id());
        });

        verify(mapper, never()).findVersionById(any());
        verify(mapper, never()).insertRelease(
                any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void 查询有效策略应返回最终值逐字段来源和稳定哈希() {
        UUID deviceId = UUID.randomUUID();
        UUID productReleaseId = UUID.randomUUID();
        UUID groupReleaseId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(mapper.findDeviceContext(deviceId)).thenReturn(Optional.of(
                new StrategyConfigurationMapper.DeviceContextRecord(deviceId, productId, "ENABLED")
        ));
        when(mapper.findCurrentFragmentsForDevices(
                productId, "ONLINE_STATUS", List.of(deviceId)
        )).thenReturn(List.of(
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        deviceId, productReleaseId, "PRODUCT", productId, null,
                        "{\"offlineThresholdSeconds\":120}"
                ),
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        deviceId, groupReleaseId, "GROUP", groupId, 800,
                        "{\"offlineThresholdSeconds\":300}"
                )
        ));

        StrategyConfigurationService.EffectiveStrategyView effective =
                service.findEffective(deviceId, "online_status");

        assertThat(effective.valid()).isTrue();
        assertThat(effective.values()).containsEntry("offlineThresholdSeconds", 300);
        assertThat(effective.sources().get("offlineThresholdSeconds").releaseId())
                .isEqualTo(groupReleaseId);
        assertThat(effective.contentHash()).startsWith("sha256:");
    }

    @Test
    void 回滚应复用历史不可变版本并创建更高序号发布() {
        UUID historicalReleaseId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        StrategyConfigurationMapper.StrategyReleaseRecord historical = release(
                historicalReleaseId, groupId, 800, 5, "{\"offlineThresholdSeconds\":120}"
        );
        when(mapper.findReleaseByIdempotency(null, "strategy-rollback-001")).thenReturn(Optional.empty());
        when(mapper.findRelease(historicalReleaseId)).thenReturn(Optional.of(historical));
        when(scopeMapper.findGroupByIdForUpdate(groupId)).thenReturn(Optional.of(group(groupId, 900)));
        when(scopeMapper.findActiveMemberIds(groupId)).thenReturn(List.of(deviceId));
        when(mapper.findCurrentFragmentsForDevices(
                productId, "ONLINE_STATUS", List.of(deviceId)
        )).thenReturn(List.of());
        when(mapper.nextReleaseSequence(productId)).thenReturn(9L);

        StrategyConfigurationService.StrategyReleaseView rollback = service.rollback(
                historicalReleaseId, "恢复稳定阈值", "strategy-rollback-001"
        );

        assertThat(rollback.action()).isEqualTo("ROLLBACK");
        assertThat(rollback.rollbackOfReleaseId()).isEqualTo(historicalReleaseId);
        assertThat(rollback.strategyVersionId()).isEqualTo(versionId);
        assertThat(rollback.groupPriority()).isEqualTo(900);
        verify(mapper, never()).publishVersion(any(), any(), anyLong(), any());
        verify(mapper).insertRelease(
                eq(rollback.id()), eq(productId), eq(versionId), eq("ONLINE_STATUS"), eq(9L),
                eq("GROUP"), eq(groupId), eq(900), eq("ROLLBACK"), eq(historicalReleaseId),
                eq("恢复稳定阈值"), eq("strategy-rollback-001"), eq(null)
        );
        verify(cacheInvalidator).invalidateAfterCommit(
                "ONLINE_STATUS",
                List.of(deviceId),
                EffectiveStrategyCacheInvalidator.InvalidationReason.ROLLBACK
        );
        verify(runtimeSnapshotService).captureDelta(
                productId, "ONLINE_STATUS", List.of(deviceId),
                StrategyRuntimeSnapshotService.ChangeReason.ROLLBACK
        );
    }

    @Test
    void 回滚幂等键不得复用普通发布或其他历史发布() {
        UUID historicalReleaseId = UUID.randomUUID();
        StrategyConfigurationMapper.StrategyReleaseRecord existing = release(
                UUID.randomUUID(), UUID.randomUUID(), 800, 7,
                "{\"offlineThresholdSeconds\":300}"
        );
        when(mapper.findReleaseByIdempotency(null, "strategy-rollback-001"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.rollback(
                historicalReleaseId, "恢复稳定阈值", "strategy-rollback-001"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-409-006"));

        verify(mapper, never()).findRelease(historicalReleaseId);
    }

    private void stubDraftForPublish(int threshold) {
        StrategyConfigurationMapper.StrategyVersionRecord draft = version("DRAFT", 4, threshold);
        when(mapper.findReleaseByIdempotency(null, "strategy-publish-001")).thenReturn(Optional.empty());
        when(mapper.findVersionById(versionId)).thenReturn(Optional.of(draft));
        when(mapper.findVersionForUpdate(versionId)).thenReturn(Optional.of(draft));
    }

    private StrategyConfigurationMapper.StrategyVersionRecord version(
            String status,
            long version,
            int threshold
    ) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new StrategyConfigurationMapper.StrategyVersionRecord(
                versionId,
                productId,
                "ONLINE_STATUS",
                3,
                status,
                "{\"offlineThresholdSeconds\":" + threshold + "}",
                "PUBLISHED".equals(status) ? "sha256:" + "0".repeat(64) : null,
                "在线状态规则",
                "PUBLISHED".equals(status) ? now : null,
                now,
                now,
                version
        );
    }

    private StrategyConfigurationMapper.StrategyVersionRecord dataQualityVersion(
            String status,
            String valuesJson,
            long version
    ) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new StrategyConfigurationMapper.StrategyVersionRecord(
                versionId,
                productId,
                "DATA_QUALITY",
                1,
                status,
                valuesJson,
                "PUBLISHED".equals(status) ? "sha256:" + "0".repeat(64) : null,
                "数据质量规则",
                "PUBLISHED".equals(status) ? now : null,
                now,
                now,
                version
        );
    }

    private StrategyScopeMapper.DeviceGroupRecord group(UUID groupId, int priority) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new StrategyScopeMapper.DeviceGroupRecord(
                groupId, productId, "haul-team", "运输一组", priority, "ACTIVE", null,
                null, now, now, 0, 1
        );
    }

    private StrategyConfigurationMapper.StrategyReleaseRecord release(
            UUID releaseId,
            UUID scopeId,
            Integer priority,
            long sequence,
            String valuesJson
    ) {
        return new StrategyConfigurationMapper.StrategyReleaseRecord(
                releaseId,
                productId,
                versionId,
                "ONLINE_STATUS",
                sequence,
                "GROUP",
                scopeId,
                priority,
                "PUBLISH",
                null,
                "历史发布",
                "sha256:" + "1".repeat(64),
                valuesJson,
                Instant.parse("2026-07-16T00:00:00Z")
        );
    }
}
