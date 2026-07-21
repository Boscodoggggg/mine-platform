package com.bosco.mine.server.modules.configuration.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.protocol.engine.mqtt.MqttBinaryProtocolPluginRegistry;
import com.bosco.mine.protocol.engine.tcp.TcpProtocolPluginRegistry;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodeContext;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodedMessage;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolPlugin;
import com.bosco.mine.server.modules.command.outbox.OutboxCommitNotifier;
import com.bosco.mine.server.modules.configuration.repository.ProtocolConfigurationMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验协议样本门禁、配置包发布和回滚的事务规则。
 */
class ProtocolConfigurationServiceTest {

    private final UUID productId = UUID.randomUUID();
    private final UUID modelVersionId = UUID.randomUUID();
    private final UUID mappingVersionId = UUID.randomUUID();
    private ProtocolConfigurationMapper mapper;
    private ProtocolConfigurationService service;
    private OutboxCommitNotifier commitNotifier;

    @BeforeEach
    void 创建协议配置服务测试环境() {
        mapper = mock(ProtocolConfigurationMapper.class);
        commitNotifier = mock(OutboxCommitNotifier.class);
        service = new ProtocolConfigurationService(
                mapper,
                mock(OperationAuditService.class),
                JsonMapper.builder().findAndAddModules().build(),
                commitNotifier
        );
    }

    @Test
    void 已发布协议的样本不允许原地修改() {
        when(mapper.findDefinitionForUpdate(mappingVersionId)).thenReturn(Optional.of(definition("PUBLISHED")));

        assertThatThrownBy(() -> service.replaceSamples(
                mappingVersionId,
                0,
                List.of(sampleCommand("valid", 1200))
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("PROTOCOL-SAMPLE-422-001"));

        verify(mapper, never()).deleteSamples(mappingVersionId);
    }

    @Test
    void 黄金样本全部符合期望时应保存通过的测试运行() {
        stubBundle("DRAFT", "NONE");

        ProtocolConfigurationService.TestRunView report = service.test(mappingVersionId);

        assertThat(report.passed()).isTrue();
        assertThat(report.totalSamples()).isOne();
        assertThat(report.failedSamples()).isZero();
        verify(mapper).insertTestRun(
                eq(report.id()), eq(mappingVersionId), any(), eq("PASSED"),
                eq(1), eq(1), eq(0), any(), any(), any(), any()
        );
    }

    @Test
    void 任一样本失败都必须阻止创建配置包和发布事件() {
        stubBundle("PUBLISHED", "NONE");
        when(mapper.findSamples(mappingVersionId)).thenReturn(List.of(
                sampleRecord("breaking", 999)
        ));

        assertThatThrownBy(() -> service.publish(new ProtocolConfigurationService.PublishCommand(
                productId, mappingVersionId, "接入厂商新设备", "publish-001"
        ))).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("CONFIG-RELEASE-422-001"));

        verify(mapper, never()).insertPackage(any(), any(), anyLong(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).insertRelease(any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void 发布应原子写入配置包发布目标和Outbox事件() {
        stubBundle("PUBLISHED", "NONE");
        when(mapper.findReleaseByIdempotency(null, "publish-001")).thenReturn(Optional.empty());
        when(mapper.nextPackageSequence(productId)).thenReturn(3L);
        when(mapper.nextReleaseSequence(productId)).thenReturn(8L);
        when(mapper.findPackageByHash(eq(productId), any())).thenReturn(Optional.empty());

        ProtocolConfigurationService.ReleaseView release = service.publish(
                new ProtocolConfigurationService.PublishCommand(
                        productId, mappingVersionId, "接入厂商新设备", "publish-001")
        );

        assertThat(release.releaseSequence()).isEqualTo(8L);
        assertThat(release.action()).isEqualTo("PUBLISH");
        assertThat(release.contentHash()).startsWith("sha256:");
        verify(mapper).insertPackage(
                eq(release.packageId()), eq(productId), eq(3L), eq(modelVersionId), eq(mappingVersionId),
                eq(release.contentHash()), any(), eq("接入厂商新设备"), eq(null)
        );
        verify(mapper).insertRelease(
                eq(release.id()), eq(productId), eq(release.packageId()), eq(8L), eq("PUBLISH"),
                eq(null), eq("接入厂商新设备"), eq("publish-001"), eq(null)
        );
        verify(mapper).insertReleaseTarget(any(), eq(release.id()), eq("PRODUCT"), eq(productId));
        verify(mapper).insertReleaseOutbox(any(), eq(release.id()), any(), any());
        verify(mapper).lockProduct(productId);
        verify(commitNotifier).notifyAfterCommit(any());
    }

    @Test
    void Mqtt二进制插件通过样本门禁后应发布V2产品绑定快照() {
        MqttBinaryProtocolPlugin plugin = new MqttBinaryProtocolPlugin() {
            @Override public String protocolKey() { return "vendor-a"; }
            @Override public String protocolVersion() { return "2.1.0"; }
            @Override public MqttBinaryDecodedMessage decode(MqttBinaryDecodeContext context, byte[] payload) {
                return new MqttBinaryDecodedMessage(
                        "boot-1", 1L, Instant.parse("2026-07-16T00:00:00Z"), false,
                        Map.of("engineRpm", 1200));
            }
        };
        service = new ProtocolConfigurationService(
                mapper,
                mock(OperationAuditService.class),
                JsonMapper.builder().findAndAddModules().build(),
                commitNotifier,
                new MqttBinaryProtocolPluginRegistry(List.of(plugin)),
                new TcpProtocolPluginRegistry(List.of())
        );
        when(mapper.findReleaseByIdempotency(null, "publish-binary")).thenReturn(Optional.empty());
        when(mapper.findDefinitionForUpdate(mappingVersionId)).thenReturn(Optional.of(binaryDefinition()));
        when(mapper.findPublishedModel(productId)).thenReturn(Optional.of(
                new ProtocolConfigurationMapper.ModelVersionRecord(modelVersionId, productId, 2)));
        when(mapper.findSamples(mappingVersionId)).thenReturn(List.of(
                new ProtocolConfigurationMapper.SampleRecord(
                        UUID.randomUUID(), mappingVersionId, "binary-valid",
                        "{\"payloadHex\":\"01\",\"deviceCode\":\"truck-001\"}", true,
                        "{\"engineRpm\":1200}", null, 0, 0)));
        when(mapper.nextPackageSequence(productId)).thenReturn(1L);
        when(mapper.nextReleaseSequence(productId)).thenReturn(1L);
        when(mapper.findPackageByHash(eq(productId), any())).thenReturn(Optional.empty());

        ProtocolConfigurationService.ReleaseView release = service.publish(
                new ProtocolConfigurationService.PublishCommand(
                        productId, mappingVersionId, "启用二进制插件", "publish-binary"));

        assertThat(release.snapshotJson()).contains(
                "\"schemaVersion\":2",
                "\"parserType\":\"JAVA_PLUGIN\"",
                "\"pluginKey\":\"vendor-a\"",
                "\"pluginVersion\":\"2.1.0\"");
        assertThat(release.snapshotJson()).doesNotContain("\"definition\"");
    }

    @Test
    void 相同幂等键应直接返回原发布而不重复回放样本() {
        ProtocolConfigurationMapper.ReleaseRecord stored = new ProtocolConfigurationMapper.ReleaseRecord(
                UUID.randomUUID(), productId, UUID.randomUUID(), 4L, "PUBLISH", null,
                "sha256:" + "a".repeat(64), "{}"
        );
        when(mapper.findReleaseByIdempotency(null, "publish-001")).thenReturn(Optional.of(stored));

        ProtocolConfigurationService.ReleaseView release = service.publish(
                new ProtocolConfigurationService.PublishCommand(
                        productId, mappingVersionId, "重复请求", "publish-001")
        );

        assertThat(release.id()).isEqualTo(stored.id());
        verify(mapper, never()).findDefinitionForUpdate(mappingVersionId);
        verify(mapper, never()).insertPackage(any(), any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 回滚必须复用历史包并创建更高序号的新发布() {
        UUID historicalReleaseId = UUID.randomUUID();
        UUID historicalPackageId = UUID.randomUUID();
        ProtocolConfigurationMapper.ReleaseRecord historical = new ProtocolConfigurationMapper.ReleaseRecord(
                historicalReleaseId, productId, historicalPackageId, 2L, "PUBLISH", null,
                "sha256:" + "b".repeat(64), "{\"schemaVersion\":1}"
        );
        when(mapper.findRelease(historicalReleaseId)).thenReturn(Optional.of(historical));
        when(mapper.findReleaseByIdempotency(null, "rollback-001")).thenReturn(Optional.empty());
        when(mapper.nextReleaseSequence(productId)).thenReturn(9L);

        ProtocolConfigurationService.ReleaseView release = service.rollback(
                historicalReleaseId, "新版本字段不兼容", "rollback-001"
        );

        assertThat(release.packageId()).isEqualTo(historicalPackageId);
        assertThat(release.releaseSequence()).isEqualTo(9L);
        assertThat(release.action()).isEqualTo("ROLLBACK");
        verify(mapper).insertRelease(
                eq(release.id()), eq(productId), eq(historicalPackageId), eq(9L), eq("ROLLBACK"),
                eq(historicalReleaseId), eq("新版本字段不兼容"), eq("rollback-001"), eq(null)
        );
        verify(mapper).insertReleaseOutbox(any(), eq(release.id()), any(), any());
        verify(mapper).lockProduct(productId);
    }

    @Test
    void 运行节点只能上报与发布一致的已加载哈希() {
        UUID releaseId = UUID.randomUUID();
        String hash = "sha256:" + "c".repeat(64);
        when(mapper.findRelease(releaseId)).thenReturn(Optional.of(
                new ProtocolConfigurationMapper.ReleaseRecord(
                        releaseId, productId, UUID.randomUUID(), 6,
                        "PUBLISH", null, hash, "{}")
        ));

        service.reportNodeStatus(releaseId, new ProtocolConfigurationService.NodeStatusCommand(
                "gateway-1", "GATEWAY", "LOADED", hash, "配置已原子切换"));

        verify(mapper).upsertNodeStatus(
                releaseId, "gateway-1", "GATEWAY", "LOADED", hash, "配置已原子切换");
        assertThatThrownBy(() -> service.reportNodeStatus(
                releaseId,
                new ProtocolConfigurationService.NodeStatusCommand(
                        "gateway-1", "GATEWAY", "LOADED",
                        "sha256:" + "d".repeat(64), "哈希错误")
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("CONFIG-RELEASE-422-002"));
    }

    @Test
    void 运行节点加载失败时应保存失败证据且无需实际哈希() {
        UUID releaseId = UUID.randomUUID();
        String hash = "sha256:" + "c".repeat(64);
        when(mapper.findRelease(releaseId)).thenReturn(Optional.of(
                new ProtocolConfigurationMapper.ReleaseRecord(
                        releaseId, productId, UUID.randomUUID(), 6,
                        "PUBLISH", null, hash, "{}")
        ));

        service.reportNodeStatus(releaseId, new ProtocolConfigurationService.NodeStatusCommand(
                "gateway-1", "GATEWAY", "FAILED", null,
                "SNAPSHOT_INVALID: 配置内容哈希不一致"));

        verify(mapper).upsertNodeStatus(
                releaseId, "gateway-1", "GATEWAY", "FAILED", null,
                "SNAPSHOT_INVALID: 配置内容哈希不一致");
    }

    @Test
    void 应查询产品发布历史和指定发布的节点加载结果() {
        UUID releaseId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-16T08:00:00Z");
        ProtocolConfigurationMapper.ReleaseSummaryRecord release =
                new ProtocolConfigurationMapper.ReleaseSummaryRecord(
                        releaseId, productId, UUID.randomUUID(), 8, "PUBLISH", null,
                        "sha256:" + "c".repeat(64), "接入运输车协议", createdAt
                );
        ProtocolConfigurationMapper.NodeStatusRecord node =
                new ProtocolConfigurationMapper.NodeStatusRecord(
                        "gateway-1", "GATEWAY", "LOADED", release.contentHash(),
                        "配置已原子切换", createdAt, createdAt
                );
        when(mapper.findReleaseSummaries(productId, 20)).thenReturn(List.of(release));
        when(mapper.findReleaseSummary(releaseId)).thenReturn(Optional.of(release));
        when(mapper.findNodeStatuses(releaseId)).thenReturn(List.of(node));

        List<ProtocolConfigurationService.ReleaseSummaryView> history =
                service.findReleases(productId, 20);
        ProtocolConfigurationService.ReleaseDetailView detail = service.findRelease(releaseId);

        assertThat(history).singleElement().extracting(
                ProtocolConfigurationService.ReleaseSummaryView::releaseSequence).isEqualTo(8L);
        assertThat(detail.nodes()).singleElement().satisfies(status -> {
            assertThat(status.nodeId()).isEqualTo("gateway-1");
            assertThat(status.status()).isEqualTo("LOADED");
        });
    }

    private void stubBundle(String status, String transform) {
        when(mapper.findDefinitionForUpdate(mappingVersionId)).thenReturn(Optional.of(definition(status)));
        when(mapper.findPublishedModel(productId)).thenReturn(Optional.of(
                new ProtocolConfigurationMapper.ModelVersionRecord(modelVersionId, productId, 2)
        ));
        when(mapper.findModelFields(modelVersionId)).thenReturn(List.of(
                new ProtocolConfigurationMapper.ModelFieldRecord("engineRpm", "INTEGER")
        ));
        when(mapper.findProtocolFields(mappingVersionId)).thenReturn(List.of(
                new ProtocolConfigurationMapper.ProtocolFieldRecord(
                        "rpm", "INTEGER", "engineRpm", transform,
                        null, null, "{}", true
                )
        ));
        when(mapper.findSamples(mappingVersionId)).thenReturn(List.of(sampleRecord("valid", 1200)));
    }

    private ProtocolConfigurationMapper.ProtocolDefinitionRecord definition(String status) {
        return new ProtocolConfigurationMapper.ProtocolDefinitionRecord(
                mappingVersionId,
                productId,
                "haul-truck",
                "haul-truck-json",
                1,
                status,
                "MQTT_JSON",
                "CONFIGURATION",
                null,
                null,
                "TRUSTED_IDENTITY",
                """
                        {"productKeyPath":"productKey","deviceCodePath":"deviceCode",
                         "bootIdPath":"bootId","sequencePath":"sequence",
                         "occurredAtPath":"occurredAt","replayedPath":"replayed"}
                        """,
                0
        );
    }

    private ProtocolConfigurationMapper.ProtocolDefinitionRecord binaryDefinition() {
        return new ProtocolConfigurationMapper.ProtocolDefinitionRecord(
                mappingVersionId, productId, "haul-truck", "vendor-a", 1, "PUBLISHED",
                "MQTT_BINARY", "JAVA_PLUGIN", "vendor-a", "2.1.0", "TRUSTED_IDENTITY", "{}", 0
        );
    }

    private ProtocolConfigurationMapper.SampleRecord sampleRecord(String name, int expectedRpm) {
        return new ProtocolConfigurationMapper.SampleRecord(
                UUID.randomUUID(), mappingVersionId, name,
                """
                        {"productKey":"haul-truck","deviceCode":"truck-001","bootId":"boot-1",
                         "sequence":1,"occurredAt":"2026-07-16T00:00:00Z","replayed":false,"rpm":1200}
                        """,
                true,
                "{\"engineRpm\":" + expectedRpm + "}",
                null,
                0,
                0
        );
    }

    private ProtocolConfigurationService.SampleCommand sampleCommand(String name, int expectedRpm) {
        return new ProtocolConfigurationService.SampleCommand(
                name,
                "{\"rpm\":1200}",
                true,
                "{\"engineRpm\":" + expectedRpm + "}",
                null,
                0
        );
    }
}
