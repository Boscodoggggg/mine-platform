package com.bosco.mine.server.modules.mine.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 人工补录或追加式修正运营执行事实的结构化请求。
 *
 * @param action 动作类型：ADD、AMEND、VOID 或 RESTORE
 * @param workOrderId ADD 动作所属作业单标识
 * @param assignmentId ADD 动作所属调度分配标识
 * @param recordStatus ADD 动作执行结果状态
 * @param startedAt ADD 动作运输开始时间
 * @param loadCompletedAt ADD 动作装载完成时间
 * @param unloadingStartedAt ADD 动作开始卸载时间
 * @param completedAt ADD 动作事实结束时间
 * @param distanceMeters ADD 或 AMEND 动作的运输距离
 * @param actualTons ADD 或 AMEND 动作的实际吨位
 * @param tonsSource ADD 或 AMEND 动作的吨位来源
 * @param projectionVersion 修正动作看到的当前有效投影版本
 * @param reason 必填人工业务原因
 */
public record OperationCorrectionRequest(
        @NotBlank @Size(max = 16) String action,
        UUID workOrderId,
        UUID assignmentId,
        @Size(max = 16) String recordStatus,
        Instant startedAt,
        Instant loadCompletedAt,
        Instant unloadingStartedAt,
        Instant completedAt,
        BigDecimal distanceMeters,
        BigDecimal actualTons,
        @Size(max = 24) String tonsSource,
        Integer projectionVersion,
        @NotBlank @Size(max = 1000) String reason
) {
}
