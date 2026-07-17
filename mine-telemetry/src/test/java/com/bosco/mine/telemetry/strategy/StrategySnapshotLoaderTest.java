package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验策略快照分页拉取、逐项校验、完整哈希门禁和原子应用调用。
 */
class StrategySnapshotLoaderTest {

    private static final UUID SNAPSHOT_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID FIRST_DEVICE_ID = UUID.fromString("019f6e00-0000-7000-8000-000000000001");
    private static final UUID SECOND_DEVICE_ID = UUID.fromString("019f6e00-0000-7000-8000-000000000002");
    private static final UUID EVENT_ID = UUID.fromString("019f6e00-0000-7000-8000-000000000010");

    @Test
    void 应分页暂存并在完整哈希一致后原子应用() throws Exception {
        StrategyPolicyRepository repository = mock(StrategyPolicyRepository.class);
        StrategySnapshotSource source = mock(StrategySnapshotSource.class);
        Map<String, Object> values = Map.of("staleAfterSeconds", 60);
        String firstHash = StrategySnapshotIntegrity.policyHash(FIRST_DEVICE_ID, values);
        String secondHash = StrategySnapshotIntegrity.policyHash(SECOND_DEVICE_ID, Map.of());
        String snapshotHash = snapshotHash(
                FIRST_DEVICE_ID, firstHash, SECOND_DEVICE_ID, secondHash);
        StrategyRuntimeChangedPayload payload = payload(snapshotHash, 2);
        when(source.fetch(SNAPSHOT_ID, null, 1)).thenReturn(page(
                snapshotHash,
                List.of(new StrategySnapshotSource.SnapshotItem(
                        FIRST_DEVICE_ID, values, firstHash)),
                FIRST_DEVICE_ID
        ));
        when(source.fetch(SNAPSHOT_ID, FIRST_DEVICE_ID, 1)).thenReturn(page(
                snapshotHash,
                List.of(new StrategySnapshotSource.SnapshotItem(
                        SECOND_DEVICE_ID, Map.of(), secondHash)),
                null
        ));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new StrategySnapshotLoader(
                repository, source, 1, new StrategyDistributionMetrics(meterRegistry)
        ).apply(EVENT_ID, payload);

        verify(repository).stage(SNAPSHOT_ID, List.of(
                new StrategyPolicyRepository.StagedPolicy(
                        FIRST_DEVICE_ID, "{\"staleAfterSeconds\":60}", firstHash)));
        verify(repository).stage(SNAPSHOT_ID, List.of(
                new StrategyPolicyRepository.StagedPolicy(
                        SECOND_DEVICE_ID, "{}", secondHash)));
        verify(repository).finalizeApply(EVENT_ID, payload);
        assertThat(counter(meterRegistry, "fetch", "success")).isEqualTo(2D);
        assertThat(counter(meterRegistry, "validate", "success")).isEqualTo(3D);
        assertThat(counter(meterRegistry, "persist", "success")).isEqualTo(2D);
        assertThat(counter(meterRegistry, "activate", "success")).isOne();
    }

    @Test
    void 快照哈希不一致不得切换本地策略() {
        StrategyPolicyRepository repository = mock(StrategyPolicyRepository.class);
        StrategySnapshotSource source = mock(StrategySnapshotSource.class);
        String itemHash = StrategySnapshotIntegrity.policyHash(FIRST_DEVICE_ID, Map.of());
        StrategyRuntimeChangedPayload payload = payload("sha256:" + "a".repeat(64), 1);
        when(source.fetch(SNAPSHOT_ID, null, 100)).thenReturn(page(
                payload.contentHash(),
                List.of(new StrategySnapshotSource.SnapshotItem(
                        FIRST_DEVICE_ID, Map.of(), itemHash)),
                null
        ));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        assertThatThrownBy(() ->
                new StrategySnapshotLoader(
                        repository, source, 100, new StrategyDistributionMetrics(meterRegistry)
                ).apply(EVENT_ID, payload))
                .isInstanceOf(PermanentStrategySnapshotException.class);

        verify(repository, never()).finalizeApply(EVENT_ID, payload);
        assertThat(counter(meterRegistry, "validate", "failure")).isOne();
    }

    @Test
    void 应使用告警类型注册表独立校验规则快照() throws Exception {
        StrategyPolicyRepository repository = mock(StrategyPolicyRepository.class);
        StrategySnapshotSource source = mock(StrategySnapshotSource.class);
        Map<String, Object> rule = new java.util.LinkedHashMap<>();
        rule.put("key", "required-rpm");
        rule.put("name", "转速缺失");
        rule.put("sourceType", "QUALITY");
        rule.put("severity", "WARNING");
        rule.put("triggerDurationSeconds", 30);
        rule.put("recoverDurationSeconds", 60);
        rule.put("cooldownSeconds", 300);
        rule.put("qualityRuleCode", "REQUIRED_MISSING");
        Map<String, Object> values = Map.of("rules", List.of(rule));
        String itemHash = StrategySnapshotIntegrity.policyHash(
                "ALARM", FIRST_DEVICE_ID, values);
        String snapshotHash = snapshotHash(FIRST_DEVICE_ID, itemHash);
        StrategyRuntimeChangedPayload payload = new StrategyRuntimeChangedPayload(
                SNAPSHOT_ID, PRODUCT_ID, "ALARM", 3, 1, snapshotHash);
        when(source.fetch(SNAPSHOT_ID, null, 100)).thenReturn(new
                StrategySnapshotSource.SnapshotPage(
                SNAPSHOT_ID, PRODUCT_ID, "ALARM", 3, 1, snapshotHash,
                List.of(new StrategySnapshotSource.SnapshotItem(
                        FIRST_DEVICE_ID, values, itemHash)), null));

        new StrategySnapshotLoader(repository, source, 100).apply(EVENT_ID, payload);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<StrategyPolicyRepository.StagedPolicy>> policies =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).stage(org.mockito.ArgumentMatchers.eq(SNAPSHOT_ID), policies.capture());
        assertThat(policies.getValue()).singleElement().satisfies(policy ->
                assertThat(policy.valuesJson())
                        .contains("\"key\":\"required-rpm\"")
                        .contains("\"enabled\":true"));
        verify(repository).finalizeApply(EVENT_ID, payload);
    }

    @Test
    void 未注册运行类型应在访问控制面前永久拒绝() {
        StrategyPolicyRepository repository = mock(StrategyPolicyRepository.class);
        StrategySnapshotSource source = mock(StrategySnapshotSource.class);
        StrategyRuntimeChangedPayload payload = new StrategyRuntimeChangedPayload(
                SNAPSHOT_ID, PRODUCT_ID, "UNREGISTERED", 1, 0,
                "sha256:" + "a".repeat(64));

        assertThatThrownBy(() ->
                new StrategySnapshotLoader(repository, source, 100).apply(EVENT_ID, payload))
                .isInstanceOf(PermanentStrategySnapshotException.class)
                .hasMessageContaining("不支持");

        verify(source, never()).fetch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).finalizeApply(EVENT_ID, payload);
    }

    private StrategySnapshotSource.SnapshotPage page(
            String contentHash,
            List<StrategySnapshotSource.SnapshotItem> items,
            UUID next
    ) {
        return new StrategySnapshotSource.SnapshotPage(
                SNAPSHOT_ID, PRODUCT_ID, "DATA_QUALITY", 7,
                2, contentHash, items, next);
    }

    private StrategyRuntimeChangedPayload payload(String hash, int itemCount) {
        return new StrategyRuntimeChangedPayload(
                SNAPSHOT_ID, PRODUCT_ID, "DATA_QUALITY", 7, itemCount, hash);
    }

    private String snapshotHash(Object... components) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int index = 0; index < components.length; index += 2) {
            digest.update((components[index] + ":" + components[index + 1] + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private double counter(SimpleMeterRegistry registry, String stage, String result) {
        return registry.get("mine.telemetry.strategy.stage")
                .tags("stage", stage, "result", result)
                .counter()
                .count();
    }
}
