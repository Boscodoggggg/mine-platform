package com.bosco.mine.telemetry.location.transform;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 将不可变来源坐标转换为矿区统一米制坐标的纯函数契约。
 */
@FunctionalInterface
public interface CoordinateTransformer {

    /**
     * 转换单个来源坐标，不读取外部状态。
     *
     * @param source 来源坐标
     * @return 固化转换版本的统一坐标
     */
    TransformedCoordinate transform(SourceCoordinate source);

    /** 坐标转换稳定算法。 */
    enum Algorithm {
        /** 坐标数值不变。 */
        IDENTITY,
        /** 四乘四列主序三维仿射矩阵。 */
        AFFINE_3D,
        /** WGS84 大地坐标转换为局部东北天坐标。 */
        WGS84_ENU
    }

    /**
     * 不同转换算法参数的封闭类型。
     */
    sealed interface TransformParameters
            permits IdentityParameters, Affine3dParameters, Wgs84EnuParameters {
    }

    /** 恒等转换的空参数。 */
    record IdentityParameters() implements TransformParameters {
    }

    /**
     * 三维仿射参数。
     *
     * @param matrix 四乘四列主序矩阵，平移量位于下标 12、13、14
     */
    record Affine3dParameters(List<Double> matrix) implements TransformParameters {

        /** 防止调用方在转换器创建后修改矩阵。 */
        public Affine3dParameters {
            if (matrix == null) {
                throw new IllegalArgumentException("仿射矩阵不能为空");
            }
            matrix = List.copyOf(matrix);
        }
    }

    /**
     * WGS84 到 ENU 的参考原点参数。
     *
     * @param originLongitude 原点经度，单位为度
     * @param originLatitude 原点纬度，单位为度
     * @param originAltitude 原点椭球高，单位为米
     */
    record Wgs84EnuParameters(
            double originLongitude,
            double originLatitude,
            double originAltitude
    ) implements TransformParameters {
    }

    /**
     * 已发布且不可变的转换运行快照。
     *
     * @param transformVersionId 坐标转换版本标识
     * @param algorithm 稳定算法
     * @param parameters 算法参数
     * @param targetBounds 矿区统一坐标边界
     */
    record TransformSnapshot(
            UUID transformVersionId,
            Algorithm algorithm,
            TransformParameters parameters,
            TargetBounds targetBounds
    ) {

        /** 校验运行快照基础字段完整。 */
        public TransformSnapshot {
            Objects.requireNonNull(transformVersionId, "坐标转换版本标识不能为空");
            Objects.requireNonNull(algorithm, "坐标转换算法不能为空");
            Objects.requireNonNull(parameters, "坐标转换参数不能为空");
            Objects.requireNonNull(targetBounds, "目标坐标边界不能为空");
        }
    }

    /**
     * 来源坐标；具体单位由来源坐标系决定。
     *
     * @param x 来源 X 分量，WGS84 时为经度
     * @param y 来源 Y 分量，WGS84 时为纬度
     * @param z 来源 Z 分量，WGS84 时为椭球高，可为空
     */
    record SourceCoordinate(double x, double y, Double z) {

        /** 拒绝会污染位置事实的非有限数值。 */
        public SourceCoordinate {
            requireFinite(x, "来源 X 坐标必须是有限数值");
            requireFinite(y, "来源 Y 坐标必须是有限数值");
            if (z != null) {
                requireFinite(z, "来源 Z 坐标必须是有限数值");
            }
        }
    }

    /**
     * 矿区统一坐标边界。
     *
     * @param minX X 轴最小值
     * @param maxX X 轴最大值
     * @param minY Y 轴最小值
     * @param maxY Y 轴最大值
     * @param minZ Z 轴最小值，可与最大值同时为空
     * @param maxZ Z 轴最大值，可与最小值同时为空
     */
    record TargetBounds(
            double minX,
            double maxX,
            double minY,
            double maxY,
            Double minZ,
            Double maxZ
    ) {

        /** 校验边界有限且严格递增。 */
        public TargetBounds {
            requireFinite(minX, "目标 X 最小边界必须是有限数值");
            requireFinite(maxX, "目标 X 最大边界必须是有限数值");
            requireFinite(minY, "目标 Y 最小边界必须是有限数值");
            requireFinite(maxY, "目标 Y 最大边界必须是有限数值");
            if (minX >= maxX || minY >= maxY) {
                throw new IllegalArgumentException("目标二维坐标边界必须严格递增");
            }
            if ((minZ == null) != (maxZ == null)) {
                throw new IllegalArgumentException("目标 Z 边界必须同时存在或同时为空");
            }
            if (minZ != null) {
                requireFinite(minZ, "目标 Z 最小边界必须是有限数值");
                requireFinite(maxZ, "目标 Z 最大边界必须是有限数值");
                if (minZ >= maxZ) {
                    throw new IllegalArgumentException("目标 Z 坐标边界必须严格递增");
                }
            }
        }

        /**
         * 验证转换结果仍位于已发布的矿区边界内。
         *
         * @param coordinate 转换结果
         * @throws OutOfBoundsException 结果超出矿区边界
         */
        public void requireContains(TransformedCoordinate coordinate) {
            Objects.requireNonNull(coordinate, "转换结果不能为空");
            boolean outside = coordinate.x() < minX || coordinate.x() > maxX
                    || coordinate.y() < minY || coordinate.y() > maxY;
            if (minZ != null) {
                outside = outside || coordinate.z() == null
                        || coordinate.z() < minZ || coordinate.z() > maxZ;
            }
            if (outside) {
                throw new OutOfBoundsException("转换结果超出矿区统一坐标边界");
            }
        }
    }

    /**
     * 已固化转换版本的矿区统一米制坐标。
     *
     * @param transformVersionId 实际使用的转换版本标识
     * @param x 统一坐标 X 分量（米）
     * @param y 统一坐标 Y 分量（米）
     * @param z 统一坐标 Z 分量（米），可为空
     */
    record TransformedCoordinate(UUID transformVersionId, double x, double y, Double z) {

        /** 确保转换器不能产出非有限事实。 */
        public TransformedCoordinate {
            Objects.requireNonNull(transformVersionId, "坐标转换版本标识不能为空");
            requireFinite(x, "统一 X 坐标必须是有限数值");
            requireFinite(y, "统一 Y 坐标必须是有限数值");
            if (z != null) {
                requireFinite(z, "统一 Z 坐标必须是有限数值");
            }
        }
    }

    /** 转换结果超出已发布矿区坐标边界。 */
    final class OutOfBoundsException extends IllegalArgumentException {

        /**
         * 创建不包含敏感坐标值的越界异常。
         *
         * @param message 固定错误摘要
         */
        public OutOfBoundsException(String message) {
            super(message);
        }
    }

    private static void requireFinite(double value, String message) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
