package com.bosco.mine.telemetry.location.transform;

import java.util.List;
import java.util.Objects;

/**
 * 校验不可变转换快照并选择对应纯函数转换器。
 */
public final class CoordinateTransformRegistry {

    private static final double AFFINE_TOLERANCE = 1E-12;

    /**
     * 从已发布转换快照创建不可变转换器。
     *
     * @param snapshot 转换运行快照
     * @return 对应算法的转换器
     */
    public CoordinateTransformer resolve(CoordinateTransformer.TransformSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "转换运行快照不能为空");
        return switch (snapshot.algorithm()) {
            case IDENTITY -> identity(snapshot);
            case AFFINE_3D -> affine3d(snapshot);
            case WGS84_ENU -> wgs84Enu(snapshot);
        };
    }

    /**
     * 使用指定快照转换单个来源坐标。
     *
     * @param snapshot 转换运行快照
     * @param source 来源坐标
     * @return 固化转换版本的统一坐标
     */
    public CoordinateTransformer.TransformedCoordinate transform(
            CoordinateTransformer.TransformSnapshot snapshot,
            CoordinateTransformer.SourceCoordinate source
    ) {
        return resolve(snapshot).transform(source);
    }

    private CoordinateTransformer identity(CoordinateTransformer.TransformSnapshot snapshot) {
        requireParameters(snapshot, CoordinateTransformer.IdentityParameters.class);
        return new IdentityTransformer(snapshot.transformVersionId(), snapshot.targetBounds());
    }

    private CoordinateTransformer affine3d(CoordinateTransformer.TransformSnapshot snapshot) {
        var parameters = requireParameters(
                snapshot, CoordinateTransformer.Affine3dParameters.class);
        double[] matrix = validateAffineMatrix(parameters.matrix());
        return new Affine3dTransformer(
                snapshot.transformVersionId(), matrix, snapshot.targetBounds());
    }

    private CoordinateTransformer wgs84Enu(CoordinateTransformer.TransformSnapshot snapshot) {
        var parameters = requireParameters(
                snapshot, CoordinateTransformer.Wgs84EnuParameters.class);
        requireFinite(parameters.originLongitude(), "WGS84 原点经度必须是有限数值");
        requireFinite(parameters.originLatitude(), "WGS84 原点纬度必须是有限数值");
        requireFinite(parameters.originAltitude(), "WGS84 原点高程必须是有限数值");
        if (parameters.originLongitude() < -180D || parameters.originLongitude() > 180D) {
            throw new IllegalArgumentException("WGS84 原点经度必须位于 -180 至 180 度");
        }
        if (parameters.originLatitude() < -90D || parameters.originLatitude() > 90D) {
            throw new IllegalArgumentException("WGS84 原点纬度必须位于 -90 至 90 度");
        }
        return new Wgs84EnuTransformer(
                snapshot.transformVersionId(), parameters, snapshot.targetBounds());
    }

    private double[] validateAffineMatrix(List<Double> values) {
        if (values.size() != 16) {
            throw new IllegalArgumentException("AFFINE_3D 参数必须包含 16 个矩阵数值");
        }
        double[] matrix = new double[16];
        for (int index = 0; index < values.size(); index++) {
            Double value = values.get(index);
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("AFFINE_3D 矩阵必须只包含有限数值");
            }
            matrix[index] = value;
        }
        if (!closeToZero(matrix[3]) || !closeToZero(matrix[7])
                || !closeToZero(matrix[11]) || !closeToOne(matrix[15])) {
            throw new IllegalArgumentException("AFFINE_3D 矩阵最后一行必须满足仿射语义");
        }
        double determinant = matrix[0] * (matrix[5] * matrix[10] - matrix[9] * matrix[6])
                - matrix[4] * (matrix[1] * matrix[10] - matrix[9] * matrix[2])
                + matrix[8] * (matrix[1] * matrix[6] - matrix[5] * matrix[2]);
        double maximum = 0D;
        int[] linearIndexes = {0, 1, 2, 4, 5, 6, 8, 9, 10};
        for (int index : linearIndexes) {
            maximum = Math.max(maximum, Math.abs(matrix[index]));
        }
        if (maximum == 0D
                || Math.abs(determinant) <= Math.pow(maximum, 3D) * AFFINE_TOLERANCE) {
            throw new IllegalArgumentException("AFFINE_3D 线性部分不能是奇异矩阵");
        }
        return matrix;
    }

    private <T extends CoordinateTransformer.TransformParameters> T requireParameters(
            CoordinateTransformer.TransformSnapshot snapshot,
            Class<T> expectedType
    ) {
        if (!expectedType.isInstance(snapshot.parameters())) {
            throw new IllegalArgumentException(
                    snapshot.algorithm() + " 算法参数类型不匹配");
        }
        return expectedType.cast(snapshot.parameters());
    }

    private boolean closeToZero(double value) {
        return Math.abs(value) <= AFFINE_TOLERANCE;
    }

    private boolean closeToOne(double value) {
        return Math.abs(value - 1D) <= AFFINE_TOLERANCE;
    }

    private void requireFinite(double value, String message) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
