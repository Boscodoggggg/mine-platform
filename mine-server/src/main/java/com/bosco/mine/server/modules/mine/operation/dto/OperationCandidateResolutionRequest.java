package com.bosco.mine.server.modules.mine.operation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 运营候选的人工关联、忽略或转补录请求。
 *
 * @param action 处置动作：ASSOCIATE、IGNORE 或 CONVERT_MANUAL
 * @param workOrderId 关联或补录目标作业单标识
 * @param assignmentId 关联或补录目标调度分配标识
 * @param manualRecord 转人工记录时的结构化补录内容
 * @param reason 必填处置原因
 * @param version 候选当前乐观锁版本
 */
public record OperationCandidateResolutionRequest(
        @NotBlank @Size(max = 24) String action,
        UUID workOrderId,
        UUID assignmentId,
        @Valid OperationCorrectionRequest manualRecord,
        @NotBlank @Size(max = 500) String reason,
        @NotNull @PositiveOrZero Long version
) {
}
