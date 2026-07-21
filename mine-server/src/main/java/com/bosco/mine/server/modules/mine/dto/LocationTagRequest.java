package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建定位标签请求。
 *
 * @param tagCode 矿区内标签编码
 * @param vendor 标签厂商
 * @param model 标签型号
 * @param batteryPercent 电池电量百分比
 * @param batteryObservedAt 电量观测时间
 */
public record LocationTagRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]{1,63}") String tagCode,
        @Size(max = 128) String vendor,
        @Size(max = 64) String model,
        @Min(0) @Max(100) Integer batteryPercent,
        Instant batteryObservedAt
) {
}
