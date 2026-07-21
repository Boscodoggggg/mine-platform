package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 新增司机资质请求。
 *
 * @param qualificationCode 人员内资质编码
 * @param name 资质名称
 * @param allowedVehicleTypes 允许驾驶的车辆类型
 * @param validFrom 有效开始日期
 * @param validTo 有效结束日期
 */
public record DriverQualificationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]{1,63}") String qualificationCode,
        @NotBlank @Size(max = 128) String name,
        @NotEmpty @Size(max = 8) List<@NotBlank String> allowedVehicleTypes,
        @NotNull LocalDate validFrom,
        @NotNull LocalDate validTo
) {
}
