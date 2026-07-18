package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 修改定位标签请求。
 *
 * @param vendor 标签厂商
 * @param model 标签型号
 * @param batteryPercent 电池电量百分比
 * @param batteryObservedAt 电量观测时间
 * @param version 期望乐观锁版本
 */
public record LocationTagUpdateRequest(
        @Size(max = 128) String vendor,
        @Size(max = 64) String model,
        @Min(0) @Max(100) Integer batteryPercent,
        Instant batteryObservedAt,
        @NotNull @Min(0) Long version
) {
}
