package com.bosco.mine.server.modules.mine.operation.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 运营候选及其人工处置结果。
 *
 * @param id 候选标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param spatialEventId 来源空间事件标识
 * @param workOrderId 可能或已确认关联的作业单标识
 * @param assignmentId 可能或已确认关联的调度分配标识
 * @param candidateType 固定候选类型
 * @param reasonCode 固定候选原因编码
 * @param occurredAt 业务发生时间
 * @param candidatePayload 匹配上下文与证据快照
 * @param status 处置状态
 * @param resolutionAction 人工处置动作
 * @param resolutionReason 人工处置原因
 * @param resolvedRecordId 转补录形成的执行记录标识
 * @param resolvedAt 处置时间
 * @param resolvedBy 处置用户标识
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record OperationCandidateResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        UUID spatialEventId,
        UUID workOrderId,
        UUID assignmentId,
        String candidateType,
        String reasonCode,
        Instant occurredAt,
        Map<String, Object> candidatePayload,
        String status,
        String resolutionAction,
        String resolutionReason,
        UUID resolvedRecordId,
        Instant resolvedAt,
        UUID resolvedBy,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    /** 创建不可修改的候选证据快照。 */
    public OperationCandidateResponse {
        candidatePayload = Collections.unmodifiableMap(
                new LinkedHashMap<>(candidatePayload));
    }
}
