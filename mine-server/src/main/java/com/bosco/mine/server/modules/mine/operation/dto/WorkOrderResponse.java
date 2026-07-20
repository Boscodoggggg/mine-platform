package com.bosco.mine.server.modules.mine.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运输作业单、冻结发布信息和调度分配响应。
 *
 * @param id 作业单标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param shiftInstanceId 所属班次实例标识
 * @param orderNumber 作业单号
 * @param title 作业标题
 * @param workType 作业类型
 * @param plannedStartAt 计划开始时刻
 * @param plannedEndAt 计划结束时刻
 * @param plannedCycles 计划循环数
 * @param plannedTons 计划吨数
 * @param priority 优先级
 * @param routeVersionId 冻结路线版本标识
 * @param loadGeofenceVersionId 冻结装载区域版本标识
 * @param unloadGeofenceVersionId 冻结卸载区域版本标识
 * @param releaseSnapshot 发布时规范化冻结快照
 * @param status 生命周期状态
 * @param releasedAt 发布时间
 * @param startedAt 启动时间
 * @param pausedAt 最近暂停时间
 * @param completedAt 完成时间
 * @param cancelledAt 取消时间
 * @param abortedAt 异常终止时间
 * @param terminalReason 终态原因
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 * @param assignments 调度分配事实
 */
public record WorkOrderResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        UUID shiftInstanceId,
        String orderNumber,
        String title,
        String workType,
        Instant plannedStartAt,
        Instant plannedEndAt,
        Integer plannedCycles,
        BigDecimal plannedTons,
        int priority,
        UUID routeVersionId,
        UUID loadGeofenceVersionId,
        UUID unloadGeofenceVersionId,
        Map<String, Object> releaseSnapshot,
        String status,
        Instant releasedAt,
        Instant startedAt,
        Instant pausedAt,
        Instant completedAt,
        Instant cancelledAt,
        Instant abortedAt,
        String terminalReason,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<Assignment> assignments
) {
    /** 对发布快照和分配列表创建不可修改副本。 */
    public WorkOrderResponse {
        releaseSnapshot = releaseSnapshot == null ? null : Map.copyOf(releaseSnapshot);
        assignments = List.copyOf(assignments);
    }

    /**
     * 一条不可原地改写的作业调度分配事实。
     *
     * @param id 分配标识
     * @param vehicleId 车辆标识
     * @param vehicleCode 车辆编码
     * @param vehicleName 车辆名称
     * @param vehicleType 车辆类型
     * @param driverPersonId 司机人员标识
     * @param driverName 司机姓名
     * @param validFrom 生效时刻
     * @param validTo 结束时刻
     * @param status 分配状态
     * @param assignmentReason 分配原因
     * @param createdAt 创建时间
     * @param version 乐观锁版本
     */
    public record Assignment(
            UUID id,
            UUID vehicleId,
            String vehicleCode,
            String vehicleName,
            String vehicleType,
            UUID driverPersonId,
            String driverName,
            Instant validFrom,
            Instant validTo,
            String status,
            String assignmentReason,
            Instant createdAt,
            long version
    ) {
    }
}
