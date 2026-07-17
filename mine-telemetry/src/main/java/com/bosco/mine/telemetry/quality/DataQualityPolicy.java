package com.bosco.mine.telemetry.quality;

import com.bosco.mine.model.strategy.DataQualityStrategyDefinition;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Telemetry 热路径使用的、已经再次类型校验的数据质量运行策略。
 *
 * @param snapshotId 不可变运行快照标识
 * @param runtimeRevision 单调运行修订号
 * @param contentHash 单设备有效策略摘要
 * @param staleAfterSeconds 陈旧阈值秒数，没有配置时为空
 * @param futureToleranceSeconds 未来时间容忍秒数，没有配置时为空
 * @param requiredProperties 必填属性集合
 * @param minimumValues 属性下限
 * @param maximumValues 属性上限
 */
public record DataQualityPolicy(
        UUID snapshotId,
        long runtimeRevision,
        String contentHash,
        Integer staleAfterSeconds,
        Integer futureToleranceSeconds,
        Set<String> requiredProperties,
        Map<String, BigDecimal> minimumValues,
        Map<String, BigDecimal> maximumValues
) {

    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** 创建并冻结类型安全的运行策略。 */
    public DataQualityPolicy {
        snapshotId = Objects.requireNonNull(snapshotId, "策略快照标识不能为空");
        if (runtimeRevision < 1L) {
            throw new IllegalArgumentException("策略运行修订号必须大于 0");
        }
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("策略内容摘要必须是规范化 SHA-256");
        }
        requiredProperties = Set.copyOf(Objects.requireNonNull(
                requiredProperties, "必填属性集合不能为空"));
        minimumValues = Map.copyOf(Objects.requireNonNull(
                minimumValues, "属性下限集合不能为空"));
        maximumValues = Map.copyOf(Objects.requireNonNull(
                maximumValues, "属性上限集合不能为空"));
    }

    /**
     * 使用共享类型注册表再次校验数据库镜像并编译为热路径对象。
     *
     * @param snapshotId 不可变运行快照标识
     * @param runtimeRevision 单调运行修订号
     * @param contentHash 单设备有效策略摘要
     * @param values 数据库中的策略 JSON 字段
     * @return 类型安全的运行策略
     */
    public static DataQualityPolicy from(
            UUID snapshotId,
            long runtimeRevision,
            String contentHash,
            Map<String, ?> values
    ) {
        Map<String, Object> normalized = new DataQualityStrategyDefinition().validate(values);
        Set<String> required = new LinkedHashSet<>();
        Map<String, BigDecimal> minimum = new LinkedHashMap<>();
        Map<String, BigDecimal> maximum = new LinkedHashMap<>();
        normalized.forEach((field, value) -> {
            if (field.startsWith(DataQualityStrategyDefinition.REQUIRED_PREFIX)
                    && Boolean.TRUE.equals(value)) {
                required.add(field.substring(
                        DataQualityStrategyDefinition.REQUIRED_PREFIX.length()));
            } else if (field.startsWith(DataQualityStrategyDefinition.MINIMUM_PREFIX)) {
                minimum.put(
                        field.substring(DataQualityStrategyDefinition.MINIMUM_PREFIX.length()),
                        (BigDecimal) value
                );
            } else if (field.startsWith(DataQualityStrategyDefinition.MAXIMUM_PREFIX)) {
                maximum.put(
                        field.substring(DataQualityStrategyDefinition.MAXIMUM_PREFIX.length()),
                        (BigDecimal) value
                );
            }
        });
        return new DataQualityPolicy(
                snapshotId,
                runtimeRevision,
                contentHash,
                (Integer) normalized.get(DataQualityStrategyDefinition.STALE_AFTER_SECONDS),
                (Integer) normalized.get(
                        DataQualityStrategyDefinition.FUTURE_TOLERANCE_SECONDS),
                required,
                minimum,
                maximum
        );
    }
}
