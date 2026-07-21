package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 修改矿区人员请求。
 *
 * @param name 人员姓名
 * @param personType 人员岗位类型
 * @param status 人员状态
 * @param version 乐观锁版本
 */
public record PersonUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "DRIVER|OPERATOR|SUPERVISOR|WORKER|VISITOR|OTHER") String personType,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @PositiveOrZero long version
) {
}
