package com.bosco.mine.telemetry.quality;

import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在生成存储点之前执行时间、必填和数值范围规则，不静默丢弃现场数据。
 */
public final class DataQualityEvaluator {

    /**
     * 未配置运行策略时保持既有质量和时间语义，仅生成数值遥测点。
     *
     * @param eventId 平台遥测事件标识
     * @param deviceId 平台设备标识
     * @param payload Gateway 标准化载荷
     * @return 不含新增证据的遥测点
     */
    public DataQualityEvaluation evaluate(
            UUID eventId,
            UUID deviceId,
            UnifiedDeviceMessage payload
    ) {
        Objects.requireNonNull(eventId, "遥测事件标识不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(payload, "标准化遥测载荷不能为空");
        List<TelemetrySample> samples = numericProperties(payload).stream()
                .map(property -> sample(
                        eventId,
                        deviceId,
                        payload,
                        property,
                        payload.quality(),
                        payload.quality() == Quality.TIME_UNTRUSTED
                ))
                .toList();
        return new DataQualityEvaluation(samples, List.of());
    }

    /**
     * 对一帧标准化遥测执行数据质量策略。
     *
     * @param eventId 平台遥测事件标识
     * @param deviceId 平台设备标识
     * @param payload Gateway 标准化载荷
     * @param policy 当前设备最后正确运行策略
     * @return 属性级样本与规则证据
     */
    public DataQualityEvaluation evaluate(
            UUID eventId,
            UUID deviceId,
            UnifiedDeviceMessage payload,
            DataQualityPolicy policy
    ) {
        Objects.requireNonNull(eventId, "遥测事件标识不能为空");
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(payload, "标准化遥测载荷不能为空");
        Objects.requireNonNull(policy, "数据质量策略不能为空");

        Map<String, Object> values = payload.properties().values();
        List<NumericProperty> numeric = numericProperties(payload);
        List<QualityEvidence> evidence = new ArrayList<>();
        boolean future = futureExceeded(payload, policy);
        boolean stale = !future && staleExceeded(payload, policy);
        BigDecimal timeOffset = future
                ? seconds(Duration.between(payload.receivedAt(), payload.occurredAt()))
                : stale
                ? seconds(Duration.between(payload.occurredAt(), payload.receivedAt()))
                : null;

        for (String propertyId : policy.requiredProperties()) {
            if (!values.containsKey(propertyId) || values.get(propertyId) == null) {
                evidence.add(evidence(
                        eventId, deviceId, propertyId, QualityRuleCode.REQUIRED_MISSING,
                        Quality.BAD, null, null, policy
                ));
            }
        }

        List<TelemetrySample> samples = new ArrayList<>(numeric.size());
        for (NumericProperty property : numeric) {
            Quality quality = payload.quality();
            if (future) {
                quality = merge(quality, Quality.TIME_UNTRUSTED);
                evidence.add(evidence(
                        eventId, deviceId, property.propertyId(),
                        QualityRuleCode.FUTURE_TIMESTAMP, Quality.TIME_UNTRUSTED,
                        timeOffset, BigDecimal.valueOf(policy.futureToleranceSeconds()), policy
                ));
            } else if (stale) {
                quality = merge(quality, Quality.UNCERTAIN);
                evidence.add(evidence(
                        eventId, deviceId, property.propertyId(),
                        QualityRuleCode.STALE_TIMESTAMP, Quality.UNCERTAIN,
                        timeOffset, BigDecimal.valueOf(policy.staleAfterSeconds()), policy
                ));
            }
            BigDecimal minimum = policy.minimumValues().get(property.propertyId());
            if (minimum != null && property.value().compareTo(minimum) < 0) {
                quality = merge(quality, Quality.BAD);
                evidence.add(evidence(
                        eventId, deviceId, property.propertyId(),
                        QualityRuleCode.BELOW_MINIMUM, Quality.BAD,
                        property.value(), minimum, policy
                ));
            }
            BigDecimal maximum = policy.maximumValues().get(property.propertyId());
            if (maximum != null && property.value().compareTo(maximum) > 0) {
                quality = merge(quality, Quality.BAD);
                evidence.add(evidence(
                        eventId, deviceId, property.propertyId(),
                        QualityRuleCode.ABOVE_MAXIMUM, Quality.BAD,
                        property.value(), maximum, policy
                ));
            }
            samples.add(sample(
                    eventId, deviceId, payload, property, quality,
                    future || payload.quality() == Quality.TIME_UNTRUSTED
            ));
        }
        return new DataQualityEvaluation(samples, evidence);
    }

    private boolean futureExceeded(
            UnifiedDeviceMessage payload,
            DataQualityPolicy policy
    ) {
        return policy.futureToleranceSeconds() != null
                && payload.occurredAt().isAfter(payload.receivedAt())
                && Duration.between(payload.receivedAt(), payload.occurredAt())
                .compareTo(Duration.ofSeconds(policy.futureToleranceSeconds())) > 0;
    }

    private boolean staleExceeded(
            UnifiedDeviceMessage payload,
            DataQualityPolicy policy
    ) {
        return policy.staleAfterSeconds() != null
                && payload.occurredAt().isBefore(payload.receivedAt())
                && Duration.between(payload.occurredAt(), payload.receivedAt())
                .compareTo(Duration.ofSeconds(policy.staleAfterSeconds())) > 0;
    }

    private TelemetrySample sample(
            UUID eventId,
            UUID deviceId,
            UnifiedDeviceMessage payload,
            NumericProperty property,
            Quality quality,
            boolean untrustedTime
    ) {
        return new TelemetrySample(
                eventId,
                deviceId,
                payload.productKey(),
                property.propertyId(),
                property.value().doubleValue(),
                quality,
                payload.bootId(),
                payload.sequence(),
                payload.messageType(),
                payload.occurredAt(),
                payload.receivedAt(),
                untrustedTime ? payload.receivedAt() : payload.occurredAt()
        );
    }

    private QualityEvidence evidence(
            UUID eventId,
            UUID deviceId,
            String propertyId,
            QualityRuleCode ruleCode,
            Quality resultQuality,
            BigDecimal observedValue,
            BigDecimal thresholdValue,
            DataQualityPolicy policy
    ) {
        return new QualityEvidence(
                eventId, deviceId, propertyId, ruleCode, resultQuality,
                observedValue, thresholdValue, policy.runtimeRevision(),
                policy.snapshotId(), policy.contentHash()
        );
    }

    private BigDecimal decimal(Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        double decimal = value.doubleValue();
        if (!Double.isFinite(decimal)) {
            throw new IllegalArgumentException("遥测数值必须是有限值");
        }
        return BigDecimal.valueOf(decimal);
    }

    private List<NumericProperty> numericProperties(UnifiedDeviceMessage payload) {
        return payload.properties().values().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Number)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new NumericProperty(
                        entry.getKey(), decimal((Number) entry.getValue())))
                .toList();
    }

    private BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds())
                .add(BigDecimal.valueOf(duration.getNano(), 9))
                .stripTrailingZeros();
    }

    private Quality merge(Quality current, Quality candidate) {
        if (current == Quality.BAD || candidate == Quality.BAD) {
            return Quality.BAD;
        }
        if (current == Quality.TIME_UNTRUSTED || candidate == Quality.TIME_UNTRUSTED) {
            return Quality.TIME_UNTRUSTED;
        }
        if (current == Quality.UNCERTAIN || candidate == Quality.UNCERTAIN) {
            return Quality.UNCERTAIN;
        }
        return Quality.GOOD;
    }

    private record NumericProperty(String propertyId, BigDecimal value) {
    }
}
