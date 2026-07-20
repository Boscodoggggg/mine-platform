package com.bosco.mine.server.modules.mine.operation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 创建或乐观修改运输作业单草稿的请求。
 *
 * @param shiftInstanceId 所属班次实例标识
 * @param orderNumber 矿区内作业单号
 * @param title 作业标题
 * @param workType 固定运输作业类型
 * @param plannedStartAt 计划开始时刻
 * @param plannedEndAt 计划结束时刻
 * @param plannedCycles 计划运输循环数
 * @param plannedTons 计划运输吨数
 * @param priority 调度优先级
 * @param version 当前乐观锁版本，创建时为零
 */
public record WorkOrderRequest(
        @NotNull UUID shiftInstanceId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{1,63}") String orderNumber,
        @NotBlank @Size(max = 128) String title,
        @NotBlank @Pattern(regexp = "HAULAGE") String workType,
        @NotNull Instant plannedStartAt,
        @NotNull Instant plannedEndAt,
        @Positive @Max(1_000_000) Integer plannedCycles,
        @DecimalMin(value = "0", inclusive = false) BigDecimal plannedTons,
        @Min(1) @Max(100) int priority,
        @PositiveOrZero long version
) {
}
