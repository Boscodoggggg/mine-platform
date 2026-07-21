package com.bosco.mine.server.modules.mine.operation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 明确营业日期窗口的生产运营汇总。
 *
 * @param siteId 矿区标识
 * @param fromDate 起始营业日期，包含
 * @param toDate 结束营业日期，包含
 * @param workOrderCount 纳入计划口径的作业单数
 * @param plannedCycles 已知计划趟次数，全未知时为空
 * @param effectiveCompletedCycles 有效完成趟次数
 * @param anomalyCycles 有效异常终止趟次数
 * @param completionRatePercent 完成率百分比，窗口内任一作业单计划趟次未知时为空
 * @param plannedTons 已知计划吨位合计，全未知时为空
 * @param actualTons 有效完成趟次已知吨位合计，全未知时为空，零吨保持零
 * @param knownTonsCycles 吨位已知的有效完成趟次数
 * @param unknownTonsCycles 吨位未知的有效完成趟次数
 * @param distanceMeters 有效完成趟次已知距离合计，全未知时为空
 * @param totalCycleSeconds 有效完成趟次总时长秒数
 * @param averageCycleSeconds 有效完成趟次平均总时长秒数
 * @param averageLoadingSeconds 装载阶段平均时长秒数
 * @param averageHaulingSeconds 运输阶段平均时长秒数
 * @param averageUnloadingSeconds 卸载阶段平均时长秒数
 * @param breakdownsTruncated 明细维度是否因安全上限被截断
 * @param workOrders 作业单完成进度
 * @param vehicles 车辆利用率
 * @param drivers 司机作业量
 */
public record OperationSummaryResponse(
        UUID siteId,
        LocalDate fromDate,
        LocalDate toDate,
        int workOrderCount,
        Long plannedCycles,
        long effectiveCompletedCycles,
        long anomalyCycles,
        BigDecimal completionRatePercent,
        BigDecimal plannedTons,
        BigDecimal actualTons,
        long knownTonsCycles,
        long unknownTonsCycles,
        BigDecimal distanceMeters,
        long totalCycleSeconds,
        BigDecimal averageCycleSeconds,
        BigDecimal averageLoadingSeconds,
        BigDecimal averageHaulingSeconds,
        BigDecimal averageUnloadingSeconds,
        boolean breakdownsTruncated,
        List<WorkOrderProgress> workOrders,
        List<VehicleUtilization> vehicles,
        List<DriverWorkload> drivers
) {

    /** 创建不可变维度明细。 */
    public OperationSummaryResponse {
        workOrders = List.copyOf(workOrders);
        vehicles = List.copyOf(vehicles);
        drivers = List.copyOf(drivers);
    }

    /**
     * 单张作业单的计划与有效产量进度。
     *
     * @param workOrderId 作业单标识
     * @param orderNumber 作业单号
     * @param title 作业标题
     * @param plannedCycles 计划趟次数
     * @param plannedTons 计划吨位
     * @param effectiveCompletedCycles 有效完成趟次数
     * @param anomalyCycles 有效异常终止趟次数
     * @param completionRatePercent 完成率百分比
     * @param actualTons 有效完成趟次已知吨位合计
     * @param knownTonsCycles 吨位已知的有效完成趟次数
     * @param unknownTonsCycles 吨位未知的有效完成趟次数
     */
    public record WorkOrderProgress(
            UUID workOrderId,
            String orderNumber,
            String title,
            Integer plannedCycles,
            BigDecimal plannedTons,
            long effectiveCompletedCycles,
            long anomalyCycles,
            BigDecimal completionRatePercent,
            BigDecimal actualTons,
            long knownTonsCycles,
            long unknownTonsCycles
    ) {
    }

    /**
     * 车辆在所参与班次开放窗口内的实际循环利用率。
     *
     * @param vehicleId 车辆标识
     * @param vehicleCode 车辆编码
     * @param vehicleName 车辆名称
     * @param shiftOpenSeconds 去重后的参与班次开放总秒数
     * @param activeSeconds 有效执行循环总秒数
     * @param utilizationRatePercent 利用率百分比
     * @param effectiveCycles 有效执行趟次数
     * @param effectiveCompletedCycles 有效完成趟次数
     * @param anomalyCycles 有效异常终止趟次数
     * @param actualTons 已知吨位合计
     * @param unknownTonsCycles 吨位未知趟次数
     */
    public record VehicleUtilization(
            UUID vehicleId,
            String vehicleCode,
            String vehicleName,
            long shiftOpenSeconds,
            long activeSeconds,
            BigDecimal utilizationRatePercent,
            long effectiveCycles,
            long effectiveCompletedCycles,
            long anomalyCycles,
            BigDecimal actualTons,
            long unknownTonsCycles
    ) {
    }

    /**
     * 司机在营业日期窗口内的有效作业量。
     *
     * @param driverPersonId 司机人员标识，未分配司机时为空
     * @param driverName 司机姓名，未分配司机时为空
     * @param effectiveCycles 有效执行趟次数
     * @param effectiveCompletedCycles 有效完成趟次数
     * @param anomalyCycles 有效异常终止趟次数
     * @param activeSeconds 有效执行循环总秒数
     * @param actualTons 已知吨位合计
     * @param unknownTonsCycles 吨位未知趟次数
     */
    public record DriverWorkload(
            UUID driverPersonId,
            String driverName,
            long effectiveCycles,
            long effectiveCompletedCycles,
            long anomalyCycles,
            long activeSeconds,
            BigDecimal actualTons,
            long unknownTonsCycles
    ) {
    }
}
