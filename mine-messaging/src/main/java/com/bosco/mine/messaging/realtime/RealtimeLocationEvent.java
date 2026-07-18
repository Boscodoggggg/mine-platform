package com.bosco.mine.messaging.realtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 定位历史事实持久化成功后发布的统一坐标实时事件。
 *
 * @param eventId 原始定位观测 UUIDv7 标识
 * @param organizationId 已固化的组织标识
 * @param siteId 已固化的矿区标识
 * @param sourceDeviceId 可信来源设备标识
 * @param tagId 定位标签标识
 * @param tagCode 定位标签稳定编码
 * @param coordinateSystemId 统一坐标系标识
 * @param vehicleId 发生时间解析的车辆标识，未绑定时为空
 * @param personId 发生时间解析的人员标识，未绑定时为空
 * @param sourceSequence TimescaleDB 已提交摄取序号
 * @param occurredAt 定位观测发生时间
 * @param receivedAt Gateway 接收时间
 * @param localX 统一米制 X 坐标
 * @param localY 统一米制 Y 坐标
 * @param localZ 统一米制 Z 坐标，二维定位可为空
 * @param speedMetersPerSecond 速度，米每秒，可为空
 * @param headingDegrees 航向角，度，可为空
 * @param accuracyMeters 水平精度，米，可为空
 * @param qualityStatus 可供业务消费的固定质量状态
 * @param qualityReason 降级原因，正常数据为空
 */
public record RealtimeLocationEvent(
        UUID eventId,
        UUID organizationId,
        UUID siteId,
        UUID sourceDeviceId,
        UUID tagId,
        String tagCode,
        UUID coordinateSystemId,
        UUID vehicleId,
        UUID personId,
        long sourceSequence,
        Instant occurredAt,
        Instant receivedAt,
        double localX,
        double localY,
        Double localZ,
        Double speedMetersPerSecond,
        Double headingDegrees,
        Double accuracyMeters,
        QualityStatus qualityStatus,
        String qualityReason
) {

    private static final Pattern TAG_CODE = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

    /** 校验实时定位事件必须对应已提交、可定位的有限坐标事实。 */
    public RealtimeLocationEvent {
        Objects.requireNonNull(eventId, "事件标识不能为空");
        if (eventId.version() != 7) {
            throw new IllegalArgumentException("事件标识必须是 UUIDv7");
        }
        Objects.requireNonNull(organizationId, "组织标识不能为空");
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        Objects.requireNonNull(sourceDeviceId, "来源设备标识不能为空");
        Objects.requireNonNull(tagId, "标签标识不能为空");
        if (tagCode == null || !TAG_CODE.matcher(tagCode).matches()) {
            throw new IllegalArgumentException("标签编码不合法");
        }
        Objects.requireNonNull(coordinateSystemId, "统一坐标系标识不能为空");
        if (sourceSequence < 1L) {
            throw new IllegalArgumentException("摄取序号必须大于 0");
        }
        Objects.requireNonNull(occurredAt, "发生时间不能为空");
        Objects.requireNonNull(receivedAt, "接收时间不能为空");
        requireFinite(localX, "X 坐标");
        requireFinite(localY, "Y 坐标");
        requireOptionalFinite(localZ, "Z 坐标");
        if (speedMetersPerSecond != null
                && (!Double.isFinite(speedMetersPerSecond) || speedMetersPerSecond < 0D)) {
            throw new IllegalArgumentException("速度必须是非负有限数值");
        }
        if (headingDegrees != null
                && (!Double.isFinite(headingDegrees)
                || headingDegrees < 0D || headingDegrees >= 360D)) {
            throw new IllegalArgumentException("航向必须是 0 至 360 左闭右开的有限数值");
        }
        if (accuracyMeters != null
                && (!Double.isFinite(accuracyMeters) || accuracyMeters <= 0D)) {
            throw new IllegalArgumentException("精度必须是正有限数值");
        }
        Objects.requireNonNull(qualityStatus, "质量状态不能为空");
        if (qualityReason != null) {
            qualityReason = qualityReason.strip();
        }
        boolean hasReason = qualityReason != null && !qualityReason.isEmpty();
        if (qualityStatus == QualityStatus.GOOD && qualityReason != null) {
            throw new IllegalArgumentException("正常定位的质量原因必须为空");
        }
        if (qualityStatus == QualityStatus.DEGRADED && !hasReason) {
            throw new IllegalArgumentException("降级定位必须提供质量原因");
        }
        if (qualityReason != null && qualityReason.length() > 128) {
            throw new IllegalArgumentException("质量原因不得超过 128 字符");
        }
    }

    /** 定位事实对实时消费者暴露的有界质量状态。 */
    public enum QualityStatus {
        /** 定位通过全部质量检查。 */
        GOOD,
        /** 定位可供展示或追溯，但不应覆盖更好当前值。 */
        DEGRADED
    }

    private static void requireFinite(double value, String fieldName) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(fieldName + "必须是有限数值");
        }
    }

    private static void requireOptionalFinite(Double value, String fieldName) {
        if (value != null) {
            requireFinite(value, fieldName);
        }
    }
}
