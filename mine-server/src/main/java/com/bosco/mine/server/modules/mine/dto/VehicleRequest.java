package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 创建矿区车辆请求。
 *
 * @param vehicleCode 矿区内车辆编码
 * @param name 车辆名称
 * @param vehicleType 车辆类型
 * @param mineNumber 矿山内部编号
 * @param licensePlate 车辆牌照
 * @param brand 车辆品牌
 * @param model 车辆型号
 * @param ratedLoadTons 额定载重吨数
 */
public record VehicleRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]{1,63}") String vehicleCode,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "HAUL_TRUCK|EXCAVATOR|LOADER|DRILL|DOZER|WATER_TRUCK|LIGHT_VEHICLE|OTHER")
        String vehicleType,
        @Size(max = 64) String mineNumber,
        @Size(max = 32) String licensePlate,
        @Size(max = 64) String brand,
        @Size(max = 64) String model,
        @DecimalMin(value = "0", inclusive = false) BigDecimal ratedLoadTons
) {
}
