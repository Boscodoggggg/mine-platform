package com.bosco.mine.server.modules.mine.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 创建或乐观修改矿区班次模板的请求。
 *
 * @param definitionCode 矿区内稳定模板编码
 * @param name 班次名称
 * @param startLocal 矿区本地开始时间
 * @param endLocal 矿区本地结束时间
 * @param crossesMidnight 是否跨越营业日期午夜
 * @param validFrom 生效营业日期
 * @param validTo 失效营业日期，长期有效时为空
 * @param version 当前乐观锁版本，创建时为零
 */
public record ShiftDefinitionRequest(
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,63}") String definitionCode,
        @NotBlank @Size(max = 128) String name,
        @NotNull LocalTime startLocal,
        @NotNull LocalTime endLocal,
        boolean crossesMidnight,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotNull @PositiveOrZero Long version
) {
}
