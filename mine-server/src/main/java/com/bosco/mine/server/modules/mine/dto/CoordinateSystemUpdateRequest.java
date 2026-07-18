package com.bosco.mine.server.modules.mine.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改矿区坐标系请求。
 *
 * @param name 坐标系名称
 * @param coordinateType 坐标类型
 * @param unit 坐标单位
 * @param epsgCode EPSG 编号
 * @param unified 是否为矿区统一坐标系
 * @param minX 横轴最小边界
 * @param maxX 横轴最大边界
 * @param minY 纵轴最小边界
 * @param maxY 纵轴最大边界
 * @param minZ 高程最小边界
 * @param maxZ 高程最大边界
 * @param version 期望乐观锁版本
 */
public record CoordinateSystemUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "LOCAL_CARTESIAN|WGS84|ENU") String coordinateType,
        @NotBlank @Pattern(regexp = "METER|MILLIMETER|DEGREE") String unit,
        @Min(1) @Max(999999) Integer epsgCode,
        @NotNull Boolean unified,
        @NotNull Double minX,
        @NotNull Double maxX,
        @NotNull Double minY,
        @NotNull Double maxY,
        Double minZ,
        Double maxZ,
        @NotNull @Min(0) Long version
) {
}
