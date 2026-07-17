package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.model.strategy.AlarmStrategyDefinition;
import com.bosco.mine.model.strategy.AggregationStrategyDefinition;
import com.bosco.mine.model.strategy.DataQualityStrategyDefinition;
import com.bosco.mine.model.strategy.EffectiveStrategyResolver;
import com.bosco.mine.model.strategy.RetentionStrategyDefinition;
import com.bosco.mine.model.strategy.ScopedStrategyFragment;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.model.strategy.StrategyDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 使用与控制面相同的类型注册表和规范化算法独立校验策略快照。
 */
public final class StrategySnapshotIntegrity {

    private static final Map<String, StrategyDefinition> DEFINITIONS = Map.of(
            DataQualityStrategyDefinition.TYPE, new DataQualityStrategyDefinition(),
            AlarmStrategyDefinition.TYPE, new AlarmStrategyDefinition(),
            RetentionStrategyDefinition.TYPE, new RetentionStrategyDefinition(),
            AggregationStrategyDefinition.TYPE, new AggregationStrategyDefinition()
    );
    private static final EffectiveStrategyResolver RESOLVER = new EffectiveStrategyResolver();

    private StrategySnapshotIntegrity() {
    }

    /**
     * 确认策略类型已经在遥测运行时注册。
     *
     * @param strategyType 稳定策略类型
     */
    public static void requireSupportedStrategyType(String strategyType) {
        if (!DEFINITIONS.containsKey(strategyType)) {
            throw new PermanentStrategySnapshotException("不支持该运行策略类型");
        }
    }

    /**
     * 校验并计算单设备最终策略哈希，空对象表示删除。
     *
     * @param deviceId 设备标识
     * @param values 策略字段
     * @return 规范化 SHA-256
     */
    public static String policyHash(UUID deviceId, Map<String, ?> values) {
        return policyHash(DataQualityStrategyDefinition.TYPE, deviceId, values);
    }

    /**
     * 校验指定运行类型并计算单设备最终策略哈希。
     *
     * @param strategyType 稳定策略类型
     * @param deviceId 设备标识
     * @param values 策略字段
     * @return 规范化 SHA-256
     */
    public static String policyHash(
            String strategyType,
            UUID deviceId,
            Map<String, ?> values
    ) {
        return validateAndNormalize(strategyType, deviceId, values).contentHash();
    }

    /** 校验策略并返回规范化字段及其摘要。 */
    public static ValidatedPolicy validateAndNormalize(UUID deviceId, Map<String, ?> values) {
        return validateAndNormalize(DataQualityStrategyDefinition.TYPE, deviceId, values);
    }

    /** 校验指定运行类型的策略并返回规范化字段及其摘要。 */
    public static ValidatedPolicy validateAndNormalize(
            String strategyType,
            UUID deviceId,
            Map<String, ?> values
    ) {
        requireSupportedStrategyType(strategyType);
        StrategyDefinition definition = DEFINITIONS.get(strategyType);
        try {
            if (values.isEmpty()) {
                return new ValidatedPolicy(Map.of(), RESOLVER.resolve(List.of()).contentHash());
            }
            Map<String, Object> normalized = definition.validate(values);
            String contentHash = RESOLVER.resolve(List.of(new ScopedStrategyFragment(
                    deviceId, StrategyScopeType.DEVICE, deviceId, null, normalized
            ))).contentHash();
            return new ValidatedPolicy(normalized, contentHash);
        } catch (IllegalArgumentException exception) {
            throw new PermanentStrategySnapshotException("单设备运行策略未通过校验", exception);
        }
    }

    /** 通过类型注册表规范化的单设备策略。 */
    public record ValidatedPolicy(Map<String, Object> values, String contentHash) {
        /** 冻结允许空值的规范化字段。 */
        public ValidatedPolicy {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /** 创建完整快照的流式摘要器。 */
    public static SnapshotDigest newSnapshotDigest() {
        return new SnapshotDigest();
    }

    /** 按稳定设备顺序累计完整快照摘要。 */
    public static final class SnapshotDigest {
        private final MessageDigest digest;

        private SnapshotDigest() {
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
            }
        }

        /** 累计一个已经独立校验的设备策略摘要。 */
        public void add(UUID deviceId, String contentHash) {
            digest.update((deviceId + ":" + contentHash + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }

        /** 完成并返回规范化 SHA-256。 */
        public String finish() {
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        }
    }
}
