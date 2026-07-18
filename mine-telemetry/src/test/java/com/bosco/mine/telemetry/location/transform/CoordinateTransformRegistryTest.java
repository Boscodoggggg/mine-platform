package com.bosco.mine.telemetry.location.transform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.bosco.mine.telemetry.location.transform.CoordinateTransformer.Affine3dParameters;
import static com.bosco.mine.telemetry.location.transform.CoordinateTransformer.Algorithm;
import static com.bosco.mine.telemetry.location.transform.CoordinateTransformer.IdentityParameters;
import static com.bosco.mine.telemetry.location.transform.CoordinateTransformer.SourceCoordinate;
import static com.bosco.mine.telemetry.location.transform.CoordinateTransformer.TargetBounds;
import static com.bosco.mine.telemetry.location.transform.CoordinateTransformer.TransformSnapshot;
import static com.bosco.mine.telemetry.location.transform.CoordinateTransformer.Wgs84EnuParameters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinateTransformRegistryTest {

    private static final UUID TRANSFORM_VERSION_ID =
            UUID.fromString("019f7000-0000-7000-8000-000000000012");
    private static final TargetBounds WIDE_BOUNDS =
            new TargetBounds(-1_000_000D, 1_000_000D, -1_000_000D, 1_000_000D,
                    -10_000D, 10_000D);
    private final CoordinateTransformRegistry registry = new CoordinateTransformRegistry();

    @Test
    void 恒等转换应保留双坐标数值并固化转换版本() {
        var result = registry.transform(snapshot(
                Algorithm.IDENTITY, new IdentityParameters(), WIDE_BOUNDS),
                new SourceCoordinate(12.5D, -6.25D, 3D));

        assertThat(result.transformVersionId()).isEqualTo(TRANSFORM_VERSION_ID);
        assertThat(result.x()).isEqualTo(12.5D);
        assertThat(result.y()).isEqualTo(-6.25D);
        assertThat(result.z()).isEqualTo(3D);
    }

    @Test
    void 四乘四列主序仿射矩阵应完成旋转缩放和平移() {
        var parameters = new Affine3dParameters(List.of(
                0D, 2D, 0D, 0D,
                -1D, 0D, 0D, 0D,
                0D, 0D, 3D, 0D,
                10D, 20D, 30D, 1D
        ));

        var result = registry.transform(
                snapshot(Algorithm.AFFINE_3D, parameters, WIDE_BOUNDS),
                new SourceCoordinate(4D, 5D, 2D));

        assertThat(result.x()).isEqualTo(5D);
        assertThat(result.y()).isEqualTo(28D);
        assertThat(result.z()).isEqualTo(36D);
    }

    @Test
    void Wgs84原点及东西北高方向应符合Wgs84椭球公开基准() {
        double equatorialRadius = 6_378_137D;
        double meridianRadiusAtEquator = 6_335_439.3272928195D;
        double oneMeterLongitude = Math.toDegrees(1D / equatorialRadius);
        double oneMeterLatitude = Math.toDegrees(1D / meridianRadiusAtEquator);
        var snapshot = snapshot(Algorithm.WGS84_ENU,
                new Wgs84EnuParameters(0D, 0D, 0D), WIDE_BOUNDS);

        var origin = registry.transform(snapshot, new SourceCoordinate(0D, 0D, 0D));
        var east = registry.transform(snapshot,
                new SourceCoordinate(oneMeterLongitude, 0D, 0D));
        var north = registry.transform(snapshot,
                new SourceCoordinate(0D, oneMeterLatitude, 0D));
        var up = registry.transform(snapshot, new SourceCoordinate(0D, 0D, 1D));

        assertThat(origin.x()).isCloseTo(0D, withinMeters(1E-9));
        assertThat(origin.y()).isCloseTo(0D, withinMeters(1E-9));
        assertThat(origin.z()).isCloseTo(0D, withinMeters(1E-9));
        assertThat(east.x()).isCloseTo(1D, withinMeters(1E-8));
        assertThat(east.y()).isCloseTo(0D, withinMeters(1E-8));
        assertThat(north.x()).isCloseTo(0D, withinMeters(1E-8));
        assertThat(north.y()).isCloseTo(1D, withinMeters(1E-8));
        assertThat(up.z()).isCloseTo(1D, withinMeters(1E-9));
    }

    @Test
    void 非有限输入和非法Wgs84范围应被拒绝() {
        var identity = snapshot(Algorithm.IDENTITY, new IdentityParameters(), WIDE_BOUNDS);
        var wgs84 = snapshot(Algorithm.WGS84_ENU,
                new Wgs84EnuParameters(116.3D, 39.9D, 80D), WIDE_BOUNDS);

        assertThatThrownBy(() -> registry.transform(identity,
                new SourceCoordinate(Double.NaN, 0D, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限");
        assertThatThrownBy(() -> registry.transform(wgs84,
                new SourceCoordinate(181D, 39.9D, 80D)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("经度");
        assertThatThrownBy(() -> registry.resolve(snapshot(Algorithm.WGS84_ENU,
                new Wgs84EnuParameters(116.3D, 91D, 80D), WIDE_BOUNDS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("纬度");
    }

    @Test
    void 非有限奇异或非仿射矩阵应被拒绝() {
        assertInvalidMatrix(List.of(
                1D, 0D, 0D, 0D,
                0D, 1D, 0D, 0D,
                0D, 0D, Double.POSITIVE_INFINITY, 0D,
                0D, 0D, 0D, 1D), "有限");
        assertInvalidMatrix(List.of(
                1D, 0D, 0D, 0D,
                0D, 0D, 0D, 0D,
                0D, 0D, 1D, 0D,
                0D, 0D, 0D, 1D), "奇异");
        assertInvalidMatrix(List.of(
                1D, 0D, 0D, 1D,
                0D, 1D, 0D, 0D,
                0D, 0D, 1D, 0D,
                0D, 0D, 0D, 1D), "仿射");
        assertThatThrownBy(() -> registry.resolve(snapshot(
                Algorithm.AFFINE_3D, new Affine3dParameters(List.of(1D, 0D)), WIDE_BOUNDS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
    }

    @Test
    void 算法参数不匹配和结果越过矿区边界应被拒绝() {
        assertThatThrownBy(() -> registry.resolve(snapshot(
                Algorithm.AFFINE_3D, new IdentityParameters(), WIDE_BOUNDS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数");

        var narrowBounds = new TargetBounds(-10D, 10D, -10D, 10D, null, null);
        assertThatThrownBy(() -> registry.transform(
                snapshot(Algorithm.IDENTITY, new IdentityParameters(), narrowBounds),
                new SourceCoordinate(10.01D, 0D, null)))
                .isInstanceOf(CoordinateTransformer.OutOfBoundsException.class)
                .hasMessageContaining("边界");
    }

    private void assertInvalidMatrix(List<Double> matrix, String message) {
        assertThatThrownBy(() -> registry.resolve(snapshot(
                Algorithm.AFFINE_3D, new Affine3dParameters(matrix), WIDE_BOUNDS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private TransformSnapshot snapshot(
            Algorithm algorithm,
            CoordinateTransformer.TransformParameters parameters,
            TargetBounds targetBounds
    ) {
        return new TransformSnapshot(
                TRANSFORM_VERSION_ID, algorithm, parameters, targetBounds);
    }

    private org.assertj.core.data.Offset<Double> withinMeters(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
