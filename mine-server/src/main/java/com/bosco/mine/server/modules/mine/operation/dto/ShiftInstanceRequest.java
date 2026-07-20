package com.bosco.mine.server.modules.mine.operation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 按模板和营业日期创建班次实例的请求。
 *
 * @param shiftDefinitionId 班次模板标识
 * @param businessDate 矿区营业日期
 * @param supervisorPersonId 值班负责人组织级人员标识
 */
public record ShiftInstanceRequest(
        @NotNull UUID shiftDefinitionId,
        @NotNull LocalDate businessDate,
        UUID supervisorPersonId
) {
}
