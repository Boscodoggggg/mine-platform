package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.model.strategy.AlarmStrategyDefinition;
import com.bosco.mine.model.strategy.DataQualityStrategyDefinition;
import com.bosco.mine.model.strategy.EffectiveStrategyResolver;
import com.bosco.mine.model.strategy.RetentionStrategyDefinition;
import com.bosco.mine.model.strategy.ScopedStrategyFragment;
import com.bosco.mine.model.strategy.StrategyDefinition;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.model.strategy.StrategyValidationException;
import com.bosco.mine.server.modules.command.outbox.OutboxCommitNotifier;
import com.bosco.mine.server.modules.strategy.repository.StrategyConfigurationMapper;
import com.bosco.mine.server.modules.strategy.repository.StrategyRuntimeSnapshotMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 把数据质量、告警和数据保留控制面的最终有效策略固化为可重放的不可变增量快照。
 */
@Service
public class StrategyRuntimeSnapshotService {

    private static final ErrorCode SNAPSHOT_INVALID =
            new ErrorCode("STRATEGY-500-001", "策略运行快照无法生成");
    private static final ErrorCode SNAPSHOT_NOT_FOUND =
            new ErrorCode("STRATEGY-404-005", "策略运行快照不存在");
    private static final int RESOLUTION_BATCH_SIZE = 1_000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final Map<String, StrategyDefinition> RUNTIME_DEFINITIONS = Map.of(
            DataQualityStrategyDefinition.TYPE, new DataQualityStrategyDefinition(),
            AlarmStrategyDefinition.TYPE, new AlarmStrategyDefinition(),
            RetentionStrategyDefinition.TYPE, new RetentionStrategyDefinition()
    );

    private final StrategyRuntimeSnapshotMapper snapshotMapper;
    private final StrategyConfigurationMapper configurationMapper;
    private final ObjectMapper objectMapper;
    private final OutboxCommitNotifier commitNotifier;
    private final EffectiveStrategyResolver resolver = new EffectiveStrategyResolver();
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建策略运行快照服务。
     *
     * @param snapshotMapper 快照与可靠事件数据接口
     * @param configurationMapper 策略发布和有效片段数据接口
     * @param objectMapper JSON 序列化器
     * @param commitNotifier 事务提交后的精确事件通知器
     */
    public StrategyRuntimeSnapshotService(
            StrategyRuntimeSnapshotMapper snapshotMapper,
            StrategyConfigurationMapper configurationMapper,
            ObjectMapper objectMapper,
            OutboxCommitNotifier commitNotifier
    ) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "快照数据接口不能为空");
        this.configurationMapper = Objects.requireNonNull(
                configurationMapper, "策略配置数据接口不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 序列化器不能为空");
        this.commitNotifier = Objects.requireNonNull(commitNotifier, "Outbox 提交通知器不能为空");
    }

    /**
     * 在当前控制面事务中生成受影响设备的最终有效策略增量。
     *
     * <p>空对象条目是显式删除语义。当前仅数据质量、告警和数据保留进入跨服务运行消费；
     * 未发布过该类型时不会制造无意义事件。</p>
     *
     * @param productId 产品标识
     * @param strategyType 策略类型
     * @param affectedDeviceIds 受影响设备标识
     * @param changeReason 触发变更原因
     * @return 已创建快照，跳过时为空
     */
    @Transactional
    public Optional<SnapshotView> captureDelta(
            UUID productId,
            String strategyType,
            Collection<UUID> affectedDeviceIds,
            ChangeReason changeReason
    ) {
        Objects.requireNonNull(productId, "产品标识不能为空");
        Objects.requireNonNull(strategyType, "策略类型不能为空");
        Objects.requireNonNull(affectedDeviceIds, "受影响设备集合不能为空");
        Objects.requireNonNull(changeReason, "快照变更原因不能为空");
        StrategyDefinition definition = RUNTIME_DEFINITIONS.get(strategyType);
        if (definition == null || affectedDeviceIds.isEmpty()) {
            return Optional.empty();
        }
        configurationMapper.lockProduct(productId);
        if (!snapshotMapper.hasRelease(productId, strategyType)) {
            return Optional.empty();
        }

        List<UUID> deviceIds = affectedDeviceIds.stream()
                .map(deviceId -> Objects.requireNonNull(deviceId, "设备标识不能为空"))
                .distinct()
                .sorted(UUID_ORDER)
                .toList();
        UUID snapshotId = idGenerator.next();
        long runtimeRevision = snapshotMapper.nextRuntimeRevision(productId, strategyType);
        List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite> items = resolveItems(
                snapshotId, productId, strategyType, definition, deviceIds);
        String contentHash = snapshotHash(items);
        snapshotMapper.insertSnapshot(
                snapshotId, productId, strategyType, runtimeRevision,
                changeReason.name(), items.size(), contentHash
        );
        if (!items.isEmpty()) {
            snapshotMapper.insertItems(items);
        }
        UUID eventId = idGenerator.next();
        snapshotMapper.insertOutbox(
                eventId,
                snapshotId,
                writeJson(Map.of(
                        "snapshotId", snapshotId,
                        "productId", productId,
                        "strategyType", strategyType,
                        "runtimeRevision", runtimeRevision,
                        "itemCount", items.size(),
                        "contentHash", contentHash
                )),
                writeJson(Map.of(
                        "partitionKey", productId.toString(),
                        "traceId", currentTraceId(eventId)
                ))
        );
        commitNotifier.notifyAfterCommit(eventId);
        return Optional.of(new SnapshotView(
                snapshotId, productId, strategyType, runtimeRevision,
                changeReason, items.size(), contentHash
        ));
    }

    /**
     * 使用设备标识游标分页读取不可变增量快照。
     *
     * @param snapshotId 快照标识
     * @param afterDeviceId 上一页最后设备标识，首页为空
     * @param limit 单页条目上限
     * @return 快照头、本页条目和下一页游标
     */
    @Transactional(readOnly = true)
    public SnapshotPage findSnapshotPage(UUID snapshotId, UUID afterDeviceId, int limit) {
        if (snapshotId == null || limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("策略运行快照分页参数不合法");
        }
        StrategyRuntimeSnapshotMapper.SnapshotRecord snapshot = snapshotMapper
                .findSnapshot(snapshotId)
                .orElseThrow(() -> new MineException(
                        SNAPSHOT_NOT_FOUND, Map.of("snapshotId", snapshotId)));
        List<StrategyRuntimeSnapshotMapper.SnapshotItemRecord> fetched =
                snapshotMapper.findItems(snapshotId, afterDeviceId, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<StrategyRuntimeSnapshotMapper.SnapshotItemRecord> pageItems = hasMore
                ? fetched.subList(0, limit)
                : fetched;
        UUID nextAfterDeviceId = hasMore
                ? pageItems.get(pageItems.size() - 1).deviceId()
                : null;
        return new SnapshotPage(
                snapshot.id(), snapshot.productId(), snapshot.strategyType(),
                snapshot.runtimeRevision(), ChangeReason.valueOf(snapshot.changeReason()),
                snapshot.itemCount(), snapshot.contentHash(), snapshot.createdAt(),
                pageItems.stream().map(item -> new SnapshotItemView(
                        item.deviceId(), item.valuesJson(), item.contentHash())).toList(),
                nextAfterDeviceId
        );
    }

    private List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite> resolveItems(
            UUID snapshotId,
            UUID productId,
            String strategyType,
            StrategyDefinition definition,
            List<UUID> deviceIds
    ) {
        Map<UUID, List<ScopedStrategyFragment>> fragmentsByDevice = new LinkedHashMap<>();
        deviceIds.forEach(deviceId -> fragmentsByDevice.put(deviceId, new ArrayList<>()));
        for (int start = 0; start < deviceIds.size(); start += RESOLUTION_BATCH_SIZE) {
            List<UUID> batch = deviceIds.subList(
                    start, Math.min(start + RESOLUTION_BATCH_SIZE, deviceIds.size()));
            for (StrategyConfigurationMapper.StrategyFragmentRecord record
                    : configurationMapper.findCurrentFragmentsForDevices(
                            productId, strategyType, batch)) {
                List<ScopedStrategyFragment> target = fragmentsByDevice.get(record.deviceId());
                if (target == null) {
                    throw snapshotInvalid(record.deviceId(), "返回了非目标设备策略片段");
                }
                target.add(new ScopedStrategyFragment(
                        record.releaseId(),
                        StrategyScopeType.valueOf(record.scopeType()),
                        record.scopeId(),
                        record.groupPriority(),
                        validateFragment(
                                definition, record.deviceId(), readMap(record.valuesJson()))
                ));
            }
        }

        List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite> items = new ArrayList<>();
        fragmentsByDevice.forEach((deviceId, fragments) -> {
            EffectiveStrategyResolver.Resolution resolution = resolver.resolve(fragments);
            if (!resolution.valid()) {
                throw snapshotInvalid(deviceId, "有效策略仍存在作用域冲突");
            }
            Map<String, Object> values = fragments.isEmpty()
                    ? Map.of()
                    : validateEffective(definition, deviceId, resolution.values());
            items.add(new StrategyRuntimeSnapshotMapper.SnapshotItemWrite(
                    snapshotId,
                    deviceId,
                    writeJson(new TreeMap<>(values)),
                    resolution.contentHash()
            ));
        });
        return List.copyOf(items);
    }

    private Map<String, Object> validateFragment(
            StrategyDefinition definition,
            UUID deviceId,
            Map<String, Object> values
    ) {
        return validate(definition, deviceId, values, "策略片段未通过类型校验");
    }

    private Map<String, Object> validateEffective(
            StrategyDefinition definition,
            UUID deviceId,
            Map<String, Object> values
    ) {
        return validate(definition, deviceId, values, "最终有效策略未通过类型校验");
    }

    private Map<String, Object> validate(
            StrategyDefinition definition,
            UUID deviceId,
            Map<String, Object> values,
            String message
    ) {
        try {
            return definition.validate(values);
        } catch (StrategyValidationException exception) {
            throw new MineException(SNAPSHOT_INVALID, Map.of(
                    "deviceId", deviceId,
                    "message", message,
                    "violations", exception.violations()
            ));
        }
    }

    private MineException snapshotInvalid(UUID deviceId, String message) {
        return new MineException(SNAPSHOT_INVALID, Map.of(
                "deviceId", deviceId,
                "message", message
        ));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException exception) {
            throw new MineException(SNAPSHOT_INVALID, Map.of("message", "策略片段 JSON 无法解析"));
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new MineException(SNAPSHOT_INVALID, Map.of("message", "策略快照 JSON 无法序列化"));
        }
    }

    private String snapshotHash(List<StrategyRuntimeSnapshotMapper.SnapshotItemWrite> items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (StrategyRuntimeSnapshotMapper.SnapshotItemWrite item : items) {
                digest.update((item.deviceId() + ":" + item.contentHash() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    private String currentTraceId(UUID eventId) {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? eventId.toString() : traceId;
    }

    /** 运行策略快照生成原因。 */
    public enum ChangeReason {
        PUBLISH,
        ROLLBACK,
        MEMBERSHIP,
        PRIORITY,
        GROUP_ARCHIVE,
        DEVICE_CREATE,
        DEVICE_ARCHIVE
    }

    /** 已创建的不可变策略运行快照。 */
    public record SnapshotView(
            UUID snapshotId,
            UUID productId,
            String strategyType,
            long runtimeRevision,
            ChangeReason changeReason,
            int itemCount,
            String contentHash
    ) {
    }

    /** 运行服务游标分页拉取的不可变策略快照。 */
    public record SnapshotPage(
            UUID snapshotId,
            UUID productId,
            String strategyType,
            long runtimeRevision,
            ChangeReason changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt,
            List<SnapshotItemView> items,
            UUID nextAfterDeviceId
    ) {
        /** 冻结本页快照条目。 */
        public SnapshotPage {
            items = List.copyOf(items);
        }
    }

    /** 运行服务拉取的单设备规范化策略条目。 */
    public record SnapshotItemView(
            UUID deviceId,
            String valuesJson,
            String contentHash
    ) {
    }
}
