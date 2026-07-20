package com.bosco.mine.server.modules.mine.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 不可变执行事实、当前有效投影和追加式修正历史响应。
 *
 * @param id 执行记录标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param shiftInstanceId 所属班次实例标识
 * @param workOrderId 所属作业单标识
 * @param assignmentId 所属调度分配标识
 * @param cycleNumber 分配内趟次序号
 * @param workType 作业类型
 * @param recordStatus 执行结果状态
 * @param recordSource 执行事实来源
 * @param vehicleId 车辆标识
 * @param driverPersonId 司机人员标识
 * @param startedAt 运输开始时间
 * @param loadCompletedAt 装载完成时间
 * @param unloadingStartedAt 开始卸载时间
 * @param completedAt 事实结束时间
 * @param baseDistanceMeters 基础事实运输距离
 * @param baseActualTons 基础事实实际吨位
 * @param baseTonsSource 基础事实吨位来源
 * @param frozenSnapshot 发生时冻结快照
 * @param createdAt 基础事实创建时间
 * @param createdBy 人工基础事实创建用户
 * @param effective 当前是否计入统计
 * @param distanceMeters 当前有效运输距离
 * @param actualTons 当前有效实际吨位
 * @param tonsSource 当前有效吨位来源
 * @param correctionVersion 当前有效修正版本
 * @param projectedAt 有效投影生成时间
 * @param corrections 按版本升序排列的追加式修正
 */
public record OperationRecordResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        UUID shiftInstanceId,
        UUID workOrderId,
        UUID assignmentId,
        long cycleNumber,
        String workType,
        String recordStatus,
        String recordSource,
        UUID vehicleId,
        UUID driverPersonId,
        Instant startedAt,
        Instant loadCompletedAt,
        Instant unloadingStartedAt,
        Instant completedAt,
        BigDecimal baseDistanceMeters,
        BigDecimal baseActualTons,
        String baseTonsSource,
        Map<String, Object> frozenSnapshot,
        Instant createdAt,
        UUID createdBy,
        boolean effective,
        BigDecimal distanceMeters,
        BigDecimal actualTons,
        String tonsSource,
        int correctionVersion,
        Instant projectedAt,
        List<Correction> corrections
) {
    /** 创建不可修改的快照和修正历史。 */
    public OperationRecordResponse {
        frozenSnapshot = Collections.unmodifiableMap(
                new LinkedHashMap<>(frozenSnapshot));
        corrections = List.copyOf(corrections);
    }

    /**
     * 一条不可修改的人工修正事实。
     *
     * @param id 修正标识
     * @param correctionVersion 修正版本
     * @param correctionType 修正类型
     * @param beforeValues 修正前完整有效值快照
     * @param afterValues 修正后完整有效值快照
     * @param reason 人工业务原因
     * @param createdAt 修正时间
     * @param createdBy 修正用户
     */
    public record Correction(
            UUID id,
            int correctionVersion,
            String correctionType,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues,
            String reason,
            Instant createdAt,
            UUID createdBy
    ) {
        /** 创建不可修改的前后快照。 */
        public Correction {
            beforeValues = Collections.unmodifiableMap(
                    new LinkedHashMap<>(beforeValues));
            afterValues = Collections.unmodifiableMap(
                    new LinkedHashMap<>(afterValues));
        }
    }
}
