package com.bosco.mine.telemetry.quality;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 与遥测事件同事务保存的结构化数据质量违规证据。
 *
 * @param eventId 原始遥测事件标识
 * @param deviceId 设备标识
 * @param propertyId 违规属性标识
 * @param ruleCode 稳定规则编码
 * @param resultQuality 本规则产生的质量结果
 * @param observedValue 观测值或时间偏移秒数，缺失规则时为空
 * @param thresholdValue 命中的阈值，缺失规则时为空
 * @param policyRuntimeRevision 策略运行修订号
 * @param policySnapshotId 策略运行快照标识
 * @param policyContentHash 单设备有效策略摘要
 */
public record QualityEvidence(
        UUID eventId,
        UUID deviceId,
        String propertyId,
        QualityRuleCode ruleCode,
        Quality resultQuality,
        BigDecimal observedValue,
        BigDecimal thresholdValue,
        long policyRuntimeRevision,
        UUID policySnapshotId,
        String policyContentHash
) {

    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** 创建并校验结构化质量证据。 */
    public QualityEvidence {
        eventId = Objects.requireNonNull(eventId, "质量证据事件标识不能为空");
        deviceId = Objects.requireNonNull(deviceId, "质量证据设备标识不能为空");
        if (eventId.version() != 7) {
            throw new IllegalArgumentException("质量证据事件标识必须是 UUIDv7");
        }
        if (propertyId == null || propertyId.isBlank() || propertyId.length() > 128) {
            throw new IllegalArgumentException("质量证据属性标识必须是 1 到 128 位非空文本");
        }
        ruleCode = Objects.requireNonNull(ruleCode, "质量证据规则编码不能为空");
        resultQuality = Objects.requireNonNull(resultQuality, "质量证据结果不能为空");
        if (resultQuality == Quality.GOOD) {
            throw new IllegalArgumentException("质量违规证据不能记录 GOOD 结果");
        }
        if (policyRuntimeRevision < 1L) {
            throw new IllegalArgumentException("质量证据策略修订号必须大于 0");
        }
        policySnapshotId = Objects.requireNonNull(
                policySnapshotId, "质量证据策略快照不能为空");
        if (policyContentHash == null || !HASH.matcher(policyContentHash).matches()) {
            throw new IllegalArgumentException("质量证据策略摘要必须是规范化 SHA-256");
        }
    }
}
