package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.server.modules.command.outbox.OutboxCommitNotifier;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyRuntimeSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验数据质量与告警有效策略增量快照、删除语义和轻量 Outbox 事件。
 */
class StrategyRuntimeSnapshotServiceTest {

    private final UUID productId = UUID.randomUUID();
    private final UUID firstDeviceId = UUID.randomUUID();
    private final UUID secondDeviceId = UUID.randomUUID();
    private StrategyRuntimeSnapshotMapper snapshotMapper;
    private StrategyConfigurationMapper configurationMapper;
    private OutboxCommitNotifier commitNotifier;
    private StrategyRuntimeSnapshotService service;

    @BeforeEach
    void 创建快照服务测试环境() {
        snapshotMapper = mock(StrategyRuntimeSnapshotMapper.class);
        configurationMapper = mock(StrategyConfigurationMapper.class);
        commitNotifier = mock(OutboxCommitNotifier.class);
        service = new StrategyRuntimeSnapshotService(
                snapshotMapper,
                configurationMapper,
                JsonMapper.builder().findAndAddModules().build(),
                commitNotifier
        );
    }

    @Test
    void 应固化稳定排序的有效策略并在同一事务写入轻量事件() {
        UUID productReleaseId = UUID.randomUUID();
        UUID deviceReleaseId = UUID.randomUUID();
        when(snapshotMapper.hasRelease(productId, "DATA_QUALITY")).thenReturn(true);
        when(snapshotMapper.nextRuntimeRevision(productId, "DATA_QUALITY")).thenReturn(7L);
        when(configurationMapper.findCurrentFragmentsForDevices(
                eq(productId), eq("DATA_QUALITY"), any()
        )).thenReturn(List.of(
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, productReleaseId, "PRODUCT", productId, null,
                        "{\"staleAfterSeconds\":300,\"required.engine.rpm\":true}"
                ),
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, deviceReleaseId, "DEVICE", firstDeviceId, null,
                        "{\"staleAfterSeconds\":60}"
                ),
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        secondDeviceId, productReleaseId, "PRODUCT", productId, null,
                        "{\"staleAfterSeconds\":300,\"required.engine.rpm\":true}"
                )
        ));

        StrategyRuntimeSnapshotService.SnapshotView snapshot = service.captureDelta(
                productId,
                "DATA_QUALITY",
                List.of(secondDeviceId, firstDeviceId, firstDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.PUBLISH
        ).orElseThrow();

        assertThat(snapshot.runtimeRevision()).isEqualTo(7);
        assertThat(snapshot.itemCount()).isEqualTo(2);
        assertThat(snapshot.contentHash()).startsWith("sha256:");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite>> items =
                ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper).insertItems(items.capture());
        assertThat(items.getValue()).extracting(StrategyRuntimeSnapshotMapper.SnapshotItemWrite::deviceId)
                .isSortedAccordingTo(java.util.Comparator.comparing(UUID::toString));
        assertThat(items.getValue()).anySatisfy(item -> {
            if (item.deviceId().equals(firstDeviceId)) {
                assertThat(item.valuesJson())
                        .isEqualTo("{\"required.engine.rpm\":true,\"staleAfterSeconds\":60}");
            }
        });
        verify(snapshotMapper).insertOutbox(
                any(), eq(snapshot.snapshotId()), anyString(), anyString());
        verify(commitNotifier).notifyAfterCommit(any());
    }

    @Test
    void 无当前片段时应写入空对象表达删除本地策略() {
        when(snapshotMapper.hasRelease(productId, "DATA_QUALITY")).thenReturn(true);
        when(snapshotMapper.nextRuntimeRevision(productId, "DATA_QUALITY")).thenReturn(8L);
        when(configurationMapper.findCurrentFragmentsForDevices(
                productId, "DATA_QUALITY", List.of(firstDeviceId)
        )).thenReturn(List.of());

        service.captureDelta(
                productId,
                "DATA_QUALITY",
                List.of(firstDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.DEVICE_ARCHIVE
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite>> items =
                ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper).insertItems(items.capture());
        assertThat(items.getValue()).singleElement().satisfies(item ->
                assertThat(item.valuesJson()).isEqualTo("{}"));
    }

    @Test
    void 告警规则集合应按作用域整体覆盖并写入独立运行修订() {
        UUID productReleaseId = UUID.randomUUID();
        UUID deviceReleaseId = UUID.randomUUID();
        when(snapshotMapper.hasRelease(productId, "ALARM")).thenReturn(true);
        when(snapshotMapper.nextRuntimeRevision(productId, "ALARM")).thenReturn(3L);
        when(configurationMapper.findCurrentFragmentsForDevices(
                productId, "ALARM", List.of(firstDeviceId)
        )).thenReturn(List.of(
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, productReleaseId, "PRODUCT", productId, null,
                        alarmValues("product-stale", "产品数据陈旧")
                ),
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, deviceReleaseId, "DEVICE", firstDeviceId, null,
                        alarmValues("device-stale", "设备数据陈旧")
                )
        ));

        StrategyRuntimeSnapshotService.SnapshotView snapshot = service.captureDelta(
                productId,
                "ALARM",
                List.of(firstDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.PUBLISH
        ).orElseThrow();

        assertThat(snapshot.strategyType()).isEqualTo("ALARM");
        assertThat(snapshot.runtimeRevision()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite>> items =
                ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper).insertItems(items.capture());
        assertThat(items.getValue()).singleElement().satisfies(item ->
                assertThat(item.valuesJson())
                        .contains("\"key\":\"device-stale\"")
                        .contains("\"enabled\":true")
                        .doesNotContain("product-stale"));
    }

    @Test
    void 保留策略对象应按作用域整体覆盖并写入独立运行修订() {
        UUID productReleaseId = UUID.randomUUID();
        UUID deviceReleaseId = UUID.randomUUID();
        when(snapshotMapper.hasRelease(productId, "RETENTION")).thenReturn(true);
        when(snapshotMapper.nextRuntimeRevision(productId, "RETENTION")).thenReturn(4L);
        when(configurationMapper.findCurrentFragmentsForDevices(
                productId, "RETENTION", List.of(firstDeviceId)
        )).thenReturn(List.of(
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, productReleaseId, "PRODUCT", productId, null,
                        retentionValues("STANDARD", 90, 180)
                ),
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, deviceReleaseId, "DEVICE", firstDeviceId, null,
                        retentionValues("LONG", 365, 730)
                )
        ));

        StrategyRuntimeSnapshotService.SnapshotView snapshot = service.captureDelta(
                productId,
                "RETENTION",
                List.of(firstDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.PUBLISH
        ).orElseThrow();

        assertThat(snapshot.strategyType()).isEqualTo("RETENTION");
        assertThat(snapshot.runtimeRevision()).isEqualTo(4);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite>> items =
                ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper).insertItems(items.capture());
        assertThat(items.getValue()).singleElement().satisfies(item ->
                assertThat(item.valuesJson())
                        .contains("\"retentionClass\":\"LONG\"")
                        .contains("\"rawTelemetryDays\":365")
                        .doesNotContain("STANDARD"));
    }

    @Test
    void 聚合策略对象应按作用域整体覆盖并写入独立运行修订() {
        UUID productReleaseId = UUID.randomUUID();
        UUID deviceReleaseId = UUID.randomUUID();
        when(snapshotMapper.hasRelease(productId, "AGGREGATION")).thenReturn(true);
        when(snapshotMapper.nextRuntimeRevision(productId, "AGGREGATION")).thenReturn(5L);
        when(configurationMapper.findCurrentFragmentsForDevices(
                productId, "AGGREGATION", List.of(firstDeviceId)
        )).thenReturn(List.of(
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, productReleaseId, "PRODUCT", productId, null,
                        aggregationValues("speed", 300, 7_200)
                ),
                new StrategyConfigurationMapper.StrategyFragmentRecord(
                        firstDeviceId, deviceReleaseId, "DEVICE", firstDeviceId, null,
                        aggregationValues("payload", 3_600, 14_400)
                )
        ));

        StrategyRuntimeSnapshotService.SnapshotView snapshot = service.captureDelta(
                productId,
                "AGGREGATION",
                List.of(firstDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.PUBLISH
        ).orElseThrow();

        assertThat(snapshot.strategyType()).isEqualTo("AGGREGATION");
        assertThat(snapshot.runtimeRevision()).isEqualTo(5);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite>> items =
                ArgumentCaptor.forClass(List.class);
        verify(snapshotMapper).insertItems(items.capture());
        assertThat(items.getValue()).singleElement().satisfies(item ->
                assertThat(item.valuesJson())
                        .contains("\"propertyId\":\"payload\"")
                        .contains("\"correctionWindowSeconds\":14400")
                        .doesNotContain("speed"));
    }

    @Test
    void 未发布数据质量策略或非运行消费类型不得制造空事件() {
        when(snapshotMapper.hasRelease(productId, "DATA_QUALITY")).thenReturn(false);

        assertThat(service.captureDelta(
                productId,
                "DATA_QUALITY",
                List.of(firstDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.DEVICE_CREATE
        )).isEmpty();
        assertThat(service.captureDelta(
                productId,
                "ONLINE_STATUS",
                List.of(firstDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.PUBLISH
        )).isEmpty();
        assertThat(service.captureDelta(
                productId,
                "DATA_QUALITY",
                List.of(),
                StrategyRuntimeSnapshotService.ChangeReason.PRIORITY
        )).isEmpty();

        verify(snapshotMapper, never()).insertSnapshot(
                any(), any(), any(), anyLong(), any(), anyInt(), anyString());
        verify(snapshotMapper, never()).insertOutbox(any(), any(), anyString(), anyString());
    }

    @Test
    void 应使用设备游标分页读取不可变快照() {
        UUID snapshotId = UUID.randomUUID();
        UUID thirdDeviceId = UUID.randomUUID();
        when(snapshotMapper.findSnapshot(snapshotId)).thenReturn(Optional.of(
                new StrategyRuntimeSnapshotMapper.SnapshotRecord(
                        snapshotId, productId, "DATA_QUALITY", 9,
                        "MEMBERSHIP", 3, "sha256:" + "a".repeat(64), Instant.now()
                )
        ));
        when(snapshotMapper.findItems(snapshotId, null, 3)).thenReturn(List.of(
                item(snapshotId, firstDeviceId, "{\"staleAfterSeconds\":60}"),
                item(snapshotId, secondDeviceId, "{\"staleAfterSeconds\":120}"),
                item(snapshotId, thirdDeviceId, "{}")
        ));

        StrategyRuntimeSnapshotService.SnapshotPage page =
                service.findSnapshotPage(snapshotId, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextAfterDeviceId()).isEqualTo(secondDeviceId);
        verify(snapshotMapper).findItems(snapshotId, null, 3);
    }

    private StrategyRuntimeSnapshotMapper.SnapshotItemRecord item(
            UUID snapshotId,
            UUID deviceId,
            String valuesJson
    ) {
        return new StrategyRuntimeSnapshotMapper.SnapshotItemRecord(
                snapshotId, deviceId, valuesJson, "sha256:" + "b".repeat(64));
    }

    private String alarmValues(String key, String name) {
        return """
                {"rules":[{
                  "key":"%s",
                  "name":"%s",
                  "sourceType":"QUALITY",
                  "severity":"WARNING",
                  "triggerDurationSeconds":30,
                  "recoverDurationSeconds":60,
                  "cooldownSeconds":300,
                  "qualityRuleCode":"STALE_TIMESTAMP"
                }]}
                """.formatted(key, name);
    }

    private String retentionValues(String retentionClass, int rawDays, int evidenceDays) {
        return """
                {"policy":{
                  "enabled":true,
                  "retentionClass":"%s",
                  "rawTelemetryDays":%d,
                  "qualityEvidenceDays":%d
                }}
                """.formatted(retentionClass, rawDays, evidenceDays);
    }

    private String aggregationValues(
            String propertyId,
            int bucketSeconds,
            int correctionWindowSeconds
    ) {
        return """
                {"policy":{
                  "enabled":true,
                  "correctionWindowSeconds":%d,
                  "rules":[{"propertyId":"%s","bucketSeconds":%d}]
                }}
                """.formatted(correctionWindowSeconds, propertyId, bucketSeconds);
    }
}
