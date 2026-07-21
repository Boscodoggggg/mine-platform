package com.bosco.mine.server.modules.mine.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 运营执行记录固定水位分页响应。
 *
 * @param siteId 矿区标识
 * @param fromDate 起始营业日期，包含
 * @param toDate 结束营业日期，包含
 * @param watermark 首页固定的矿区运营业务水位
 * @param nextCursor 下一页不透明游标
 * @param items 当前页最新有效执行投影
 */
public record OperationRecordPageResponse(
        UUID siteId,
        LocalDate fromDate,
        LocalDate toDate,
        long watermark,
        String nextCursor,
        List<Item> items
) {

    /** 创建不可变记录页。 */
    public OperationRecordPageResponse {
        items = List.copyOf(items);
    }

    /**
     * 一条执行事实的最新有效列表投影。
     *
     * @param recordId 执行记录标识
     * @param recordSequence 记录首次进入运营事件流的矿区业务序号
     * @param shiftInstanceId 班次实例标识
     * @param businessDate 归属营业日期
     * @param workOrderId 作业单标识
     * @param orderNumber 作业单号
     * @param orderTitle 作业标题
     * @param assignmentId 调度分配标识
     * @param cycleNumber 分配内趟次序号
     * @param recordStatus 执行结果状态
     * @param vehicleId 车辆标识
     * @param vehicleCode 车辆编码
     * @param vehicleName 车辆名称
     * @param driverPersonId 司机人员标识
     * @param driverName 司机姓名
     * @param startedAt 运输开始时间
     * @param loadCompletedAt 装载完成时间
     * @param unloadingStartedAt 开始卸载时间
     * @param completedAt 事实结束时间
     * @param totalDurationSeconds 总循环时长秒数
     * @param loadingSeconds 装载阶段时长秒数
     * @param haulingSeconds 运输阶段时长秒数
     * @param unloadingSeconds 卸载阶段时长秒数
     * @param distanceMeters 当前有效运输距离
     * @param actualTons 当前有效实际吨位，未知时为空，零吨保持零
     * @param tonsKnown 实际吨位是否已知
     * @param tonsSource 当前有效吨位来源
     * @param correctionVersion 当前修正版本
     * @param projectedAt 最新有效投影生成时间
     */
    public record Item(
            UUID recordId,
            long recordSequence,
            UUID shiftInstanceId,
            LocalDate businessDate,
            UUID workOrderId,
            String orderNumber,
            String orderTitle,
            UUID assignmentId,
            long cycleNumber,
            String recordStatus,
            UUID vehicleId,
            String vehicleCode,
            String vehicleName,
            UUID driverPersonId,
            String driverName,
            Instant startedAt,
            Instant loadCompletedAt,
            Instant unloadingStartedAt,
            Instant completedAt,
            long totalDurationSeconds,
            Long loadingSeconds,
            Long haulingSeconds,
            Long unloadingSeconds,
            BigDecimal distanceMeters,
            BigDecimal actualTons,
            boolean tonsKnown,
            String tonsSource,
            int correctionVersion,
            Instant projectedAt
    ) {
    }
}
