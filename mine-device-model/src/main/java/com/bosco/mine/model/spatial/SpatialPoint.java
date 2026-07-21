package com.bosco.mine.model.spatial;

/**
 * 矿区统一坐标系中的一个待分类位置点。
 *
 * @param x 统一坐标 X（米）
 * @param y 统一坐标 Y（米）
 * @param z 可选统一坐标 Z（米）
 */
public record SpatialPoint(double x, double y, Double z) {

    /** 校验位置坐标只包含有限数字。 */
    public SpatialPoint {
        requireFinite(x, "位置 X 坐标必须是有限数字");
        requireFinite(y, "位置 Y 坐标必须是有限数字");
        if (z != null) {
            requireFinite(z, "位置 Z 坐标必须是有限数字");
        }
        x = normalizeZero(x);
        y = normalizeZero(y);
        z = z == null ? null : normalizeZero(z);
    }

    private static void requireFinite(double value, String message) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static double normalizeZero(double value) {
        return value == 0D ? 0D : value;
    }
}
