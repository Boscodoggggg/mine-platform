package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 修改矿区人员请求。
 *
 * @param name 人员姓名
 * @param personType 人员岗位类型
 * @param phone 手机号码明文，仅用于本次写入加密
 * @param certificateNumber 证件号码明文，仅用于本次写入加密
 * @param employmentFrom 任职开始日期
 * @param employmentTo 任职结束日期
 * @param status 人员状态
 * @param version 乐观锁版本
 */
public record PersonUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "DRIVER|OPERATOR|SUPERVISOR|WORKER|VISITOR|OTHER") String personType,
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 64) String certificateNumber,
        @NotNull LocalDate employmentFrom,
        LocalDate employmentTo,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @PositiveOrZero long version
) {
}
