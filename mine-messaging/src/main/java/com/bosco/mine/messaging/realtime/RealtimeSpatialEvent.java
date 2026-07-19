package com.bosco.mine.messaging.realtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 进入、离开或持续停留空间事实提交后发布的实时事件。
 *
 * @param eventId 空间业务事件 UUIDv7 标识
 * @param organizationId 事件所属客户组织标识
 * @param siteId 事件所属矿区标识
 * @param zoneType 途径点或电子围栏区域类型
 * @param zoneId 稳定区域对象标识
 * @param zoneVersionId 不可变发布区域版本标识
 * @param tagId 发生事件的定位标签标识
 * @param vehicleId 发生时解析到的车辆标识
 * @param personId 发生时解析到的人员标识
 * @param eventType 进入、离开或停留事件类型
 * @param anchorLocationEventId 触发状态转换的位置事实标识
 * @param previousState 事件前稳定状态
 * @param currentState 事件后稳定状态
 * @param confirmationPoints 触发转换的连续确认点数量
 * @param ingestSequence 锚点位置事实的平台摄入序号
 * @param runtimeRevision 判定使用的定位运行修订号
 * @param occurredAt 空间业务事件发生时间
 * @param evidence 有界坐标、分类和发布版本证据
 */
public record RealtimeSpatialEvent(
        UUID eventId,
        UUID organizationId,
        UUID siteId,
        ZoneType zoneType,
        UUID zoneId,
        UUID zoneVersionId,
        UUID tagId,
        UUID vehicleId,
        UUID personId,
        EventType eventType,
        UUID anchorLocationEventId,
        StableState previousState,
        StableState currentState,
        int confirmationPoints,
        long ingestSequence,
        long runtimeRevision,
        Instant occurredAt,
        Evidence evidence
) {

    /** 校验事件身份、状态转换、顺序字段和证据完整。 */
    public RealtimeSpatialEvent {
        Objects.requireNonNull(eventId, "空间事件标识不能为空");
        if (eventId.version() != 7) {
            throw new IllegalArgumentException("空间事件标识必须是 UUIDv7");
        }
        Objects.requireNonNull(organizationId, "组织标识不能为空");
        Objects.requireNonNull(siteId, "矿区标识不能为空");
        Objects.requireNonNull(zoneType, "区域类型不能为空");
        Objects.requireNonNull(zoneId, "区域标识不能为空");
        Objects.requireNonNull(zoneVersionId, "区域版本标识不能为空");
        Objects.requireNonNull(tagId, "定位标签标识不能为空");
        Objects.requireNonNull(eventType, "空间事件类型不能为空");
        Objects.requireNonNull(anchorLocationEventId, "锚点位置事件标识不能为空");
        Objects.requireNonNull(previousState, "事件前状态不能为空");
        Objects.requireNonNull(currentState, "事件后状态不能为空");
        Objects.requireNonNull(occurredAt, "事件发生时间不能为空");
        Objects.requireNonNull(evidence, "空间事件证据不能为空");
        if (vehicleId != null && personId != null) {
            throw new IllegalArgumentException("空间事件不能同时绑定车辆和人员");
        }
        if (confirmationPoints < 1 || ingestSequence < 1L || runtimeRevision < 1L) {
            throw new IllegalArgumentException("确认点数、摄入序号和运行修订必须为正数");
        }
        boolean validTransition = switch (eventType) {
            case ENTER -> previousState == StableState.OUTSIDE
                    && currentState == StableState.INSIDE;
            case EXIT -> previousState == StableState.INSIDE
                    && currentState == StableState.OUTSIDE;
            case DWELL -> previousState == StableState.INSIDE
                    && currentState == StableState.INSIDE;
        };
        if (!validTransition) {
            throw new IllegalArgumentException("空间事件类型与状态转换不一致");
        }
    }

    /** 空间区域运行类型。 */
    public enum ZoneType {
        /** 圆形途径点判定区域。 */
        WAYPOINT,
        /** 多边形电子围栏判定区域。 */
        GEOFENCE
    }

    /** 空间业务事件类型。 */
    public enum EventType {
        /** 从区域外进入区域内。 */
        ENTER,
        /** 从区域内离开区域。 */
        EXIT,
        /** 在区域内持续达到阈值。 */
        DWELL
    }

    /** 空间状态机稳定状态。 */
    public enum StableState {
        /** 已确认在区域外。 */
        OUTSIDE,
        /** 已确认在区域内。 */
        INSIDE
    }

    /**
     * 可供审计和前端详情展示的有界空间判定证据。
     *
     * @param localX 统一矿区坐标 X（米）
     * @param localY 统一矿区坐标 Y（米）
     * @param localZ 可选统一矿区坐标 Z（米）
     * @param classification 固定几何分类结果
     * @param qualityStatus 固定位置质量状态
     * @param qualityReason 可选位置质量原因
     * @param zoneContentHash 不可变发布区域内容摘要
     */
    public record Evidence(
            double localX,
            double localY,
            Double localZ,
            String classification,
            String qualityStatus,
            String qualityReason,
            String zoneContentHash
    ) {

        private static final Set<String> CLASSIFICATIONS =
                Set.of("INSIDE", "OUTSIDE", "BOUNDARY", "HEIGHT_UNKNOWN");
        private static final Set<String> QUALITY_STATUSES = Set.of("GOOD", "DEGRADED");
        private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");

        /** 校验坐标有限、枚举受控且摘要格式固定。 */
        public Evidence {
            requireFinite(localX, "空间事件 X 坐标必须是有限数值");
            requireFinite(localY, "空间事件 Y 坐标必须是有限数值");
            if (localZ != null) {
                requireFinite(localZ, "空间事件 Z 坐标必须是有限数值");
            }
            if (!CLASSIFICATIONS.contains(classification)) {
                throw new IllegalArgumentException("空间事件几何分类不受支持");
            }
            if (!QUALITY_STATUSES.contains(qualityStatus)) {
                throw new IllegalArgumentException("空间事件质量状态不受支持");
            }
            if (qualityReason != null) {
                qualityReason = qualityReason.strip();
                if (qualityReason.isEmpty() || qualityReason.length() > 128) {
                    throw new IllegalArgumentException("空间事件质量原因长度不合法");
                }
            }
            if (!HASH.matcher(Objects.requireNonNull(
                    zoneContentHash, "区域版本摘要不能为空")).matches()) {
                throw new IllegalArgumentException("区域版本摘要格式不合法");
            }
        }

        private static void requireFinite(double value, String message) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
