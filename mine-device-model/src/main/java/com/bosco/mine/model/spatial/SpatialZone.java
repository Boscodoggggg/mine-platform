package com.bosco.mine.model.spatial;

import java.util.List;

/**
 * 可由定位热路径确定性判定的圆形途径点或多边形围栏。
 */
public sealed interface SpatialZone permits SpatialZone.Circle, SpatialZone.Polygon {

    /** @return 边界保持原稳定状态的容差（米） */
    double boundaryToleranceMeters();

    /** @return 可选最小高度（米） */
    Double minZ();

    /** @return 可选最大高度（米） */
    Double maxZ();

    /**
     * 圆形途径点判定区域。
     *
     * @param centerX 圆心 X（米）
     * @param centerY 圆心 Y（米）
     * @param radiusMeters 半径（米）
     * @param boundaryToleranceMeters 边界容差（米）
     * @param minZ 可选最小高度（米）
     * @param maxZ 可选最大高度（米）
     */
    record Circle(
            double centerX,
            double centerY,
            double radiusMeters,
            double boundaryToleranceMeters,
            Double minZ,
            Double maxZ
    ) implements SpatialZone {

        /** 校验圆形和高度参数。 */
        public Circle {
            requireFinite(centerX, "圆心 X 坐标必须是有限数字");
            requireFinite(centerY, "圆心 Y 坐标必须是有限数字");
            if (!Double.isFinite(radiusMeters) || radiusMeters <= 0D) {
                throw new IllegalArgumentException("圆形区域半径必须是大于零的有限数字");
            }
            requireTolerance(boundaryToleranceMeters);
            if (boundaryToleranceMeters >= radiusMeters) {
                throw new IllegalArgumentException("边界容差必须小于圆形区域半径");
            }
            requireHeight(minZ, maxZ);
            centerX = normalizeZero(centerX);
            centerY = normalizeZero(centerY);
            minZ = normalizeNullableZero(minZ);
            maxZ = normalizeNullableZero(maxZ);
        }
    }

    /**
     * 简单多边形电子围栏判定区域。
     *
     * @param vertices 不重复末端顶点的有序二维顶点
     * @param boundaryToleranceMeters 边界容差（米）
     * @param minZ 可选最小高度（米）
     * @param maxZ 可选最大高度（米）
     */
    record Polygon(
            List<Vertex> vertices,
            double boundaryToleranceMeters,
            Double minZ,
            Double maxZ
    ) implements SpatialZone {

        /** 校验多边形基础参数，拓扑由分类器统一校验。 */
        public Polygon {
            if (vertices == null || vertices.size() < 3) {
                throw new IllegalArgumentException("多边形至少需要三个顶点");
            }
            if (vertices.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("多边形顶点不能为空");
            }
            vertices = List.copyOf(vertices);
            requireTolerance(boundaryToleranceMeters);
            requireHeight(minZ, maxZ);
            minZ = normalizeNullableZero(minZ);
            maxZ = normalizeNullableZero(maxZ);
        }
    }

    /**
     * 多边形有序二维顶点。
     *
     * @param x 统一坐标 X（米）
     * @param y 统一坐标 Y（米）
     */
    record Vertex(double x, double y) {

        /** 校验顶点坐标只包含有限数字。 */
        public Vertex {
            requireFinite(x, "顶点 X 坐标必须是有限数字");
            requireFinite(y, "顶点 Y 坐标必须是有限数字");
            x = normalizeZero(x);
            y = normalizeZero(y);
        }
    }

    private static void requireTolerance(double value) {
        if (!Double.isFinite(value) || value < 0D || value > 1000D) {
            throw new IllegalArgumentException("边界容差必须是 0 到 1000 米的有限数字");
        }
    }

    private static void requireHeight(Double minZ, Double maxZ) {
        if ((minZ == null) != (maxZ == null)) {
            throw new IllegalArgumentException("高度范围必须同时提供最小值和最大值");
        }
        if (minZ != null) {
            requireFinite(minZ, "最小高度必须是有限数字");
            requireFinite(maxZ, "最大高度必须是有限数字");
            if (minZ >= maxZ) {
                throw new IllegalArgumentException("高度最小值必须小于最大值");
            }
        }
    }

    private static void requireFinite(double value, String message) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static double normalizeZero(double value) {
        return value == 0D ? 0D : value;
    }

    private static Double normalizeNullableZero(Double value) {
        return value == null ? null : normalizeZero(value);
    }
}
