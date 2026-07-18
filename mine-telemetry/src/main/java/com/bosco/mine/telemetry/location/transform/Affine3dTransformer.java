package com.bosco.mine.telemetry.location.transform;

import java.util.Objects;
import java.util.UUID;

/** 使用四乘四列主序矩阵执行三维仿射转换。 */
final class Affine3dTransformer implements CoordinateTransformer {

    private final UUID transformVersionId;
    private final double[] matrix;
    private final TargetBounds targetBounds;

    /**
     * 创建已完成矩阵语义校验的仿射转换器。
     *
     * @param transformVersionId 转换版本标识
     * @param matrix 四乘四列主序矩阵
     * @param targetBounds 目标坐标边界
     */
    Affine3dTransformer(UUID transformVersionId, double[] matrix, TargetBounds targetBounds) {
        this.transformVersionId = Objects.requireNonNull(transformVersionId);
        this.matrix = Objects.requireNonNull(matrix).clone();
        this.targetBounds = Objects.requireNonNull(targetBounds);
    }

    /** {@inheritDoc} */
    @Override
    public TransformedCoordinate transform(SourceCoordinate source) {
        Objects.requireNonNull(source, "来源坐标不能为空");
        double z = source.z() == null ? 0D : source.z();
        double x = matrix[0] * source.x() + matrix[4] * source.y()
                + matrix[8] * z + matrix[12];
        double y = matrix[1] * source.x() + matrix[5] * source.y()
                + matrix[9] * z + matrix[13];
        double transformedZ = matrix[2] * source.x() + matrix[6] * source.y()
                + matrix[10] * z + matrix[14];
        var result = new TransformedCoordinate(
                transformVersionId, x, y, transformedZ);
        targetBounds.requireContains(result);
        return result;
    }
}
