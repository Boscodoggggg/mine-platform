package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.messaging.strategy.StrategyRuntimeChangedPayload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.bosco.mine.telemetry.strategy.StrategyDistributionMetrics.Stage;
import static com.bosco.mine.telemetry.strategy.StrategyDistributionMetrics.StageResult;

/**
 * 分页拉取控制面不可变快照，独立校验后委托仓储原子切换本地策略镜像。
 */
public class StrategySnapshotLoader {

    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

    private final StrategyPolicyRepository repository;
    private final StrategySnapshotSource source;
    private final int pageSize;
    private final StrategyDistributionMetrics metrics;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建策略快照加载器。
     *
     * @param repository 持久镜像仓储
     * @param source 受信任控制面快照源
     * @param pageSize 单次拉取条目数
     */
    public StrategySnapshotLoader(
            StrategyPolicyRepository repository,
            StrategySnapshotSource source,
            int pageSize
    ) {
        this(
                repository,
                source,
                pageSize,
                new StrategyDistributionMetrics(new SimpleMeterRegistry())
        );
    }

    /**
     * 创建带固定阶段指标的策略快照加载器。
     *
     * @param repository 持久镜像仓储
     * @param source 受信任控制面快照源
     * @param pageSize 单次拉取条目数
     * @param metrics 策略分发指标记录器
     */
    public StrategySnapshotLoader(
            StrategyPolicyRepository repository,
            StrategySnapshotSource source,
            int pageSize,
            StrategyDistributionMetrics metrics
    ) {
        this.repository = Objects.requireNonNull(repository, "策略镜像仓储不能为空");
        this.source = Objects.requireNonNull(source, "策略快照源不能为空");
        if (pageSize < 1 || pageSize > 1_000) {
            throw new IllegalArgumentException("策略快照页大小必须在 1 到 1000 之间");
        }
        this.pageSize = pageSize;
        this.metrics = Objects.requireNonNull(metrics, "策略分发指标不能为空");
    }

    /**
     * 加载、校验并应用一个策略运行事件。
     *
     * @param eventId RabbitMQ 事件标识
     * @param payload 快照定位和完整性声明
     */
    public void apply(UUID eventId, StrategyRuntimeChangedPayload payload) {
        if (repository.isEventProcessed(eventId)) {
            return;
        }
        UUID cursor = null;
        UUID previousDeviceId = null;
        int itemCount = 0;
        int pageCount = 0;
        StrategySnapshotIntegrity.SnapshotDigest digest =
                StrategySnapshotIntegrity.newSnapshotDigest();
        try {
            do {
                if (++pageCount > payload.itemCount() / pageSize + 2) {
                    throw new PermanentStrategySnapshotException("策略快照分页游标未能收敛");
                }
                StrategySnapshotSource.SnapshotPage page;
                try {
                    page = source.fetch(payload.snapshotId(), cursor, pageSize);
                    metrics.stage(Stage.FETCH, StageResult.SUCCESS);
                } catch (RuntimeException exception) {
                    metrics.stage(Stage.FETCH, StageResult.FAILURE);
                    throw exception;
                }
                List<StrategyPolicyRepository.StagedPolicy> staged = new ArrayList<>();
                UUID next;
                try {
                    validateHeader(page, payload);
                    for (StrategySnapshotSource.SnapshotItem item : page.items()) {
                        if (previousDeviceId != null
                                && UUID_ORDER.compare(previousDeviceId, item.deviceId()) >= 0) {
                            throw new PermanentStrategySnapshotException(
                                    "策略快照设备条目未稳定递增排序");
                        }
                        StrategySnapshotIntegrity.ValidatedPolicy validated =
                                StrategySnapshotIntegrity.validateAndNormalize(
                                        item.deviceId(), item.values());
                        if (!validated.contentHash().equals(item.contentHash())) {
                            throw new PermanentStrategySnapshotException("单设备策略哈希不一致");
                        }
                        staged.add(new StrategyPolicyRepository.StagedPolicy(
                                item.deviceId(), writeJson(validated.values()), item.contentHash()));
                        digest.add(item.deviceId(), item.contentHash());
                        previousDeviceId = item.deviceId();
                        itemCount++;
                        if (itemCount > payload.itemCount()) {
                            throw new PermanentStrategySnapshotException(
                                    "策略快照条目超过事件声明数量");
                        }
                    }
                    next = page.nextAfterDeviceId();
                    if (next != null && (page.items().isEmpty()
                            || !next.equals(page.items().getLast().deviceId())
                            || next.equals(cursor))) {
                        throw new PermanentStrategySnapshotException(
                                "策略快照下一页游标不合法");
                    }
                    metrics.stage(Stage.VALIDATE, StageResult.SUCCESS);
                } catch (RuntimeException exception) {
                    metrics.stage(Stage.VALIDATE, StageResult.FAILURE);
                    throw exception;
                }
                try {
                    repository.stage(payload.snapshotId(), List.copyOf(staged));
                    metrics.stage(Stage.PERSIST, StageResult.SUCCESS);
                } catch (RuntimeException exception) {
                    metrics.stage(Stage.PERSIST, StageResult.FAILURE);
                    throw exception;
                }
                cursor = next;
            } while (cursor != null);

            try {
                if (itemCount != payload.itemCount()) {
                    throw new PermanentStrategySnapshotException(
                            "策略快照条目数与事件声明不一致");
                }
                if (!digest.finish().equals(payload.contentHash())) {
                    throw new PermanentStrategySnapshotException("策略快照完整哈希不一致");
                }
                metrics.stage(Stage.VALIDATE, StageResult.SUCCESS);
            } catch (RuntimeException exception) {
                metrics.stage(Stage.VALIDATE, StageResult.FAILURE);
                throw exception;
            }
            try {
                repository.finalizeApply(eventId, payload);
                metrics.stage(Stage.ACTIVATE, StageResult.SUCCESS);
            } catch (RuntimeException exception) {
                metrics.stage(Stage.ACTIVATE, StageResult.FAILURE);
                throw exception;
            }
        } catch (PermanentStrategySnapshotException exception) {
            repository.discard(payload.snapshotId());
            throw exception;
        }
    }

    private void validateHeader(
            StrategySnapshotSource.SnapshotPage page,
            StrategyRuntimeChangedPayload payload
    ) {
        if (!payload.snapshotId().equals(page.snapshotId())
                || !payload.productId().equals(page.productId())
                || !payload.strategyType().equals(page.strategyType())
                || payload.runtimeRevision() != page.runtimeRevision()
                || payload.itemCount() != page.itemCount()
                || !payload.contentHash().equals(page.contentHash())) {
            throw new PermanentStrategySnapshotException("策略快照头与轻量事件不一致");
        }
    }

    private String writeJson(Map<String, Object> values) {
        try {
            return jsonMapper.writeValueAsString(new java.util.TreeMap<>(values));
        } catch (JacksonException exception) {
            throw new PermanentStrategySnapshotException("规范化策略无法序列化", exception);
        }
    }
}
