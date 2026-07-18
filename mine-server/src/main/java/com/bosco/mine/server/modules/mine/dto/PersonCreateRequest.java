package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 创建矿区人员请求。
 *
 * @param personCode 组织内人员编码
 * @param name 人员姓名
 * @param personType 人员岗位类型
 * @param phone 手机号码明文，仅用于本次写入加密
 * @param certificateNumber 证件号码明文，仅用于本次写入加密
 * @param assignmentFrom 初始矿区任职开始日期
 * @param assignmentTo 初始矿区任职结束日期
 */
public record PersonCreateRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]{1,63}") String personCode,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "DRIVER|OPERATOR|SUPERVISOR|WORKER|VISITOR|OTHER") String personType,
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 64) String certificateNumber,
        @NotNull LocalDate assignmentFrom,
        LocalDate assignmentTo
) {
}
