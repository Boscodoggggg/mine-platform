package com.bosco.mine.server.modules.mine.operation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 矿区班次实例及冻结模板响应。
 *
 * @param id 班次实例标识
 * @param organizationId 所属组织标识
 * @param siteId 所属矿区标识
 * @param shiftDefinitionId 来源模板标识
 * @param businessDate 营业日期
 * @param plannedStartAt 计划开始 UTC 时刻
 * @param plannedEndAt 计划结束 UTC 时刻
 * @param actualStartAt 实际开始时刻
 * @param actualEndAt 实际结束时刻
 * @param supervisorPersonId 值班负责人标识
 * @param definitionSnapshot 创建时冻结的模板快照
 * @param status 生命周期状态
 * @param openedAt 开启时间
 * @param closedAt 关账时间
 * @param cancelledAt 取消时间
 * @param cancellationReason 取消原因
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 乐观锁版本
 */
public record ShiftInstanceResponse(
        UUID id,
        UUID organizationId,
        UUID siteId,
        UUID shiftDefinitionId,
        LocalDate businessDate,
        Instant plannedStartAt,
        Instant plannedEndAt,
        Instant actualStartAt,
        Instant actualEndAt,
        UUID supervisorPersonId,
        Map<String, Object> definitionSnapshot,
        String status,
        Instant openedAt,
        Instant closedAt,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
    /** 对冻结模板快照创建不可修改副本。 */
    public ShiftInstanceResponse {
        definitionSnapshot = Map.copyOf(definitionSnapshot);
    }
}
