package com.bosco.mine.gateway.configuration;

import com.bosco.mine.protocol.engine.json.MqttJsonExecutionPlan;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolCompiler;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolDefinition;
import com.bosco.mine.protocol.engine.snapshot.ProtocolSnapshotHasher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存 Gateway 当前生效的产品协议执行计划，并按发布序号原子热切换。
 *
 * <p>候选快照必须先通过内容哈希、结构一致性和协议编译，随后才会用 CAS 替换当前不可变索引。
 * 任一校验失败都不会影响上一份可用配置。</p>
 */
public final class ProtocolSnapshotRegistry {

    private final boolean required;
    private final AtomicReference<Map<UUID, ActiveProtocol>> activeByProduct =
            new AtomicReference<>(Map.of());
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final ProtocolSnapshotHasher hasher = new ProtocolSnapshotHasher();
    private final MqttJsonProtocolCompiler compiler = new MqttJsonProtocolCompiler();

    /**
     * 创建协议快照注册表。
     *
     * @param required 未加载产品配置时是否禁止继续解析
     */
    public ProtocolSnapshotRegistry(boolean required) {
        this.required = required;
    }

    /**
     * 完整校验并激活一个更高序号的产品配置发布。
     *
     * @param candidate 候选发布快照
     * @return 已激活或幂等保持不变
     */
    public ActivationResult activate(ConfigurationSnapshot candidate) {
        Objects.requireNonNull(candidate, "候选配置快照不能为空");
        ActiveProtocol compiled = compile(candidate);
        while (true) {
            Map<UUID, ActiveProtocol> current = activeByProduct.get();
            ActiveProtocol active = current.get(candidate.productId());
            if (active != null) {
                if (candidate.releaseSequence() < active.releaseSequence()) {
                    throw invalid("候选发布序号低于当前生效序号");
                }
                if (candidate.releaseSequence() == active.releaseSequence()) {
                    if (candidate.releaseId().equals(active.releaseId())
                            && candidate.contentHash().equals(active.contentHash())) {
                        return ActivationResult.UNCHANGED;
                    }
                    throw invalid("相同序号对应不同配置发布或内容哈希");
                }
            }
            boolean duplicatedProductKey = current.values().stream().anyMatch(existing ->
                    existing.productKey().equals(compiled.productKey())
                            && !existing.productId().equals(compiled.productId()));
            if (duplicatedProductKey) {
                throw invalid("产品编码已被另一个产品配置占用");
            }
            Map<UUID, ActiveProtocol> next = new LinkedHashMap<>(current);
            next.put(candidate.productId(), compiled);
            if (activeByProduct.compareAndSet(current, Map.copyOf(next))) {
                return ActivationResult.ACTIVATED;
            }
        }
    }

    /**
     * 按产品编码查询当前执行计划。
     *
     * @param productKey 产品编码
     * @return 当前生效协议
     */
    public Optional<ActiveProtocol> find(String productKey) {
        if (productKey == null) return Optional.empty();
        return activeByProduct.get().values().stream()
                .filter(active -> productKey.equals(active.productKey()))
                .findFirst();
    }

    /**
     * 获取产品当前协议；强制模式下缺失会拒绝设备消息。
     *
     * @param productKey 产品编码
     * @return 当前生效协议
     */
    public ActiveProtocol require(String productKey) {
        return find(productKey).orElseThrow(() -> invalid("产品尚未加载可用协议配置: " + productKey));
    }

    /** @return 是否要求所有产品先加载动态配置 */
    public boolean required() {
        return required;
    }

    /** @return 当前全部产品的只读生效状态 */
    public List<SnapshotStatus> statuses() {
        return activeByProduct.get().values().stream()
                .map(active -> new SnapshotStatus(
                        active.productId(), active.productKey(), active.releaseId(),
                        active.releaseSequence(), active.contentHash(), active.protocolVersion()))
                .toList();
    }

    private ActiveProtocol compile(ConfigurationSnapshot candidate) {
        String actualHash;
        try {
            actualHash = hasher.sha256(candidate.snapshotJson());
        } catch (IllegalArgumentException exception) {
            throw new InvalidConfigurationSnapshotException("配置快照不是合法 JSON", exception);
        }
        if (!candidate.contentHash().equals(actualHash)) {
            throw invalid("配置快照内容哈希与发布事件不一致");
        }
        try {
            SnapshotDocument document = jsonMapper.readValue(
                    candidate.snapshotJson(), SnapshotDocument.class);
            if (document.schemaVersion() != 1
                    || !candidate.productId().equals(document.productId())
                    || document.modelVersionId() == null
                    || document.protocolMappingVersionId() == null
                    || document.productKey() == null
                    || document.productKey().isBlank()) {
                throw invalid("配置快照结构或产品标识不一致");
            }
            MqttJsonExecutionPlan plan = compiler.compile(document.definition());
            return new ActiveProtocol(
                    candidate.releaseId(), candidate.productId(), candidate.packageId(),
                    candidate.releaseSequence(), candidate.contentHash(),
                    document.productKey(), plan
            );
        } catch (InvalidConfigurationSnapshotException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidConfigurationSnapshotException("配置快照协议定义无法编译", exception);
        }
    }

    private InvalidConfigurationSnapshotException invalid(String message) {
        return new InvalidConfigurationSnapshotException(message);
    }

    private record SnapshotDocument(
            int schemaVersion,
            UUID productId,
            String productKey,
            UUID modelVersionId,
            UUID protocolMappingVersionId,
            MqttJsonProtocolDefinition definition
    ) {
    }

    /** 候选快照激活结果。 */
    public enum ActivationResult {
        /** 候选已原子替换旧配置。 */
        ACTIVATED,
        /** 相同发布重复送达，配置保持不变。 */
        UNCHANGED
    }

    /**
     * 一个产品当前可直接执行的不可变协议配置。
     *
     * @param releaseId 配置发布标识
     * @param productId 产品标识
     * @param packageId 配置包标识
     * @param releaseSequence 发布序号
     * @param contentHash 快照哈希
     * @param productKey 产品编码
     * @param plan 已编译执行计划
     */
    public record ActiveProtocol(
            UUID releaseId,
            UUID productId,
            UUID packageId,
            long releaseSequence,
            String contentHash,
            String productKey,
            MqttJsonExecutionPlan plan
    ) {
        /** @return 对外审计使用的稳定协议版本文本 */
        public String protocolVersion() {
            return plan.protocolKey() + ":v" + plan.version();
        }
    }

    /** 当前产品配置的可观测状态。 */
    public record SnapshotStatus(
            UUID productId,
            String productKey,
            UUID releaseId,
            long releaseSequence,
            String contentHash,
            String protocolVersion
    ) {
    }
}
