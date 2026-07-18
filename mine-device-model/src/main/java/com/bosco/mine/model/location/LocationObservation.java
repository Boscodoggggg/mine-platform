package com.bosco.mine.model.location;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gateway 根据可信设备身份生成的强类型原始定位观测。
 *
 * <p>该契约不提前决定矿区、车辆、人员或统一坐标；
 * Telemetry 必须使用已激活快照按发生时间解析运行语义。</p>
 *
 * @param observationId UUIDv7 观测标识，重试时保持不变
 * @param sourceDeviceId Gateway 凭据快照确认的可信来源设备标识
 * @param tagCode 矿区内定位标签稳定编码
 * @param coordinateSystemCode 源坐标系稳定编码
 * @param x 源坐标 X
 * @param y 源坐标 Y
 * @param z 源坐标 Z，二维设备可为空
 * @param speedMetersPerSecond 速度，米每秒，不支持时可为空
 * @param headingDegrees 航向角，度，取值范围为左闭右开的 0 至 360
 * @param accuracyMeters 水平精度，米，不支持时可为空
 * @param bootId 设备启动周期标识
 * @param sequence 设备启动周期内严格正序号
 * @param occurredAt 设备声明的发生时间
 * @param receivedAt Gateway 接收时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocationObservation(
        UUID observationId,
        UUID sourceDeviceId,
        String tagCode,
        String coordinateSystemCode,
        double x,
        double y,
        Double z,
        Double speedMetersPerSecond,
        Double headingDegrees,
        Double accuracyMeters,
        String bootId,
        long sequence,
        Instant occurredAt,
        Instant receivedAt
) {

    private static final Pattern BUSINESS_CODE =
            Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final int MAX_BOOT_ID_LENGTH = 128;

    /** 校验定位观测的可追溯、可解析与有限数值边界。 */
    public LocationObservation {
        Objects.requireNonNull(observationId, "观测标识不能为空");
        if (observationId.version() != 7) {
            throw new IllegalArgumentException("观测标识必须是 UUIDv7");
        }
        Objects.requireNonNull(sourceDeviceId, "可信来源设备标识不能为空");
        tagCode = requireBusinessCode(tagCode, "标签编码");
        coordinateSystemCode = requireBusinessCode(coordinateSystemCode, "坐标系编码");
        requireFinite(x, "X 坐标");
        requireFinite(y, "Y 坐标");
        requireOptionalFinite(z, "Z 坐标");
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
        bootId = requireBootId(bootId);
        if (sequence < 1L) {
            throw new IllegalArgumentException("启动周期内序号必须大于 0");
        }
        Objects.requireNonNull(occurredAt, "发生时间不能为空");
        Objects.requireNonNull(receivedAt, "接收时间不能为空");
    }

    private static String requireBusinessCode(String value, String fieldName) {
        if (value == null || !BUSINESS_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + "必须是 2 至 64 位小写字母、数字或连字符稳定编码");
        }
        return value;
    }

    private static String requireBootId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("启动标识不能为空");
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_BOOT_ID_LENGTH) {
            throw new IllegalArgumentException("启动标识长度必须为 1 至 128");
        }
        return normalized;
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
