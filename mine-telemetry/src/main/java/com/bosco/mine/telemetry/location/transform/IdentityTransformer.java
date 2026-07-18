package com.bosco.mine.telemetry.location.transform;

import java.util.Objects;
import java.util.UUID;

/** 保留来源坐标数值的恒等转换器。 */
final class IdentityTransformer implements CoordinateTransformer {

    private final UUID transformVersionId;
    private final TargetBounds targetBounds;

    /**
     * 创建绑定不可变版本和边界的恒等转换器。
     *
     * @param transformVersionId 转换版本标识
     * @param targetBounds 目标坐标边界
     */
    IdentityTransformer(UUID transformVersionId, TargetBounds targetBounds) {
        this.transformVersionId = Objects.requireNonNull(transformVersionId);
        this.targetBounds = Objects.requireNonNull(targetBounds);
    }

    /** {@inheritDoc} */
    @Override
    public TransformedCoordinate transform(SourceCoordinate source) {
        Objects.requireNonNull(source, "来源坐标不能为空");
        var result = new TransformedCoordinate(
                transformVersionId, source.x(), source.y(), source.z());
        targetBounds.requireContains(result);
        return result;
    }
}
