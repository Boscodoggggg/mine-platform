package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        new StrategySnapshotLoader(repository, source, 1).apply(EVENT_ID, payload);

        verify(repository).stage(SNAPSHOT_ID, List.of(
                new StrategyPolicyRepository.StagedPolicy(
                        FIRST_DEVICE_ID, "{\"staleAfterSeconds\":60}", firstHash)));
        verify(repository).stage(SNAPSHOT_ID, List.of(
                new StrategyPolicyRepository.StagedPolicy(
                        SECOND_DEVICE_ID, "{}", secondHash)));
        verify(repository).finalizeApply(EVENT_ID, payload);
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

        assertThatThrownBy(() ->
                new StrategySnapshotLoader(repository, source, 100).apply(EVENT_ID, payload))
                .isInstanceOf(PermanentStrategySnapshotException.class);

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
}
