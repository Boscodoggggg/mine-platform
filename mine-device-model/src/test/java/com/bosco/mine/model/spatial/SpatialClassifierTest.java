package com.bosco.mine.model.spatial;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证空间分类在圆形、凹多边形、边界和高度约束下保持确定性。
 */
class SpatialClassifierTest {

    private final SpatialClassifier classifier = new SpatialClassifier();

    @Test
    void 圆形区域应区分内部外部边界和高度缺失() {
        SpatialZone.Circle zone = new SpatialZone.Circle(
                10D, 20D, 5D, 0.5D, 0D, 10D);

        assertThat(classifier.classify(zone, new SpatialPoint(10D, 20D, 5D)))
                .isEqualTo(SpatialClassification.INSIDE);
        assertThat(classifier.classify(zone, new SpatialPoint(16D, 20D, 5D)))
                .isEqualTo(SpatialClassification.OUTSIDE);
        assertThat(classifier.classify(zone, new SpatialPoint(14.7D, 20D, 5D)))
                .isEqualTo(SpatialClassification.BOUNDARY);
        assertThat(classifier.classify(zone, new SpatialPoint(10D, 20D, null)))
                .isEqualTo(SpatialClassification.HEIGHT_UNKNOWN);
        assertThat(classifier.classify(zone, new SpatialPoint(10D, 20D, 11D)))
                .isEqualTo(SpatialClassification.OUTSIDE);
    }

    @Test
    void 凹多边形应使用射线法正确分类并把边和顶点视为边界() {
        SpatialZone.Polygon zone = polygon(0.1D, null, null,
                vertex(0D, 0D),
                vertex(6D, 0D),
                vertex(6D, 2D),
                vertex(2D, 2D),
                vertex(2D, 6D),
                vertex(0D, 6D));

        assertThat(classifier.classify(zone, new SpatialPoint(1D, 5D, null)))
                .isEqualTo(SpatialClassification.INSIDE);
        assertThat(classifier.classify(zone, new SpatialPoint(4D, 4D, null)))
                .isEqualTo(SpatialClassification.OUTSIDE);
        assertThat(classifier.classify(zone, new SpatialPoint(2.05D, 4D, null)))
                .isEqualTo(SpatialClassification.BOUNDARY);
        assertThat(classifier.classify(zone, new SpatialPoint(0D, 0D, null)))
                .isEqualTo(SpatialClassification.BOUNDARY);
    }

    @Test
    void 带高度多边形应只在二维命中后要求有效高度() {
        SpatialZone.Polygon zone = polygon(0D, -5D, 5D,
                vertex(0D, 0D), vertex(10D, 0D),
                vertex(10D, 10D), vertex(0D, 10D));

        assertThat(classifier.classify(zone, new SpatialPoint(20D, 20D, null)))
                .isEqualTo(SpatialClassification.OUTSIDE);
        assertThat(classifier.classify(zone, new SpatialPoint(5D, 5D, null)))
                .isEqualTo(SpatialClassification.HEIGHT_UNKNOWN);
        assertThat(classifier.classify(zone, new SpatialPoint(5D, 5D, -5D)))
                .isEqualTo(SpatialClassification.INSIDE);
        assertThat(classifier.classify(zone, new SpatialPoint(5D, 5D, 6D)))
                .isEqualTo(SpatialClassification.OUTSIDE);
    }

    @Test
    void 自相交重复顶点和零面积多边形必须被拒绝() {
        SpatialZone.Polygon selfIntersecting = polygon(0D, null, null,
                vertex(0D, 0D), vertex(10D, 10D),
                vertex(0D, 10D), vertex(10D, 0D));
        SpatialZone.Polygon duplicate = polygon(0D, null, null,
                vertex(0D, 0D), vertex(10D, 0D),
                vertex(10D, 0D), vertex(0D, 10D));
        SpatialZone.Polygon flat = polygon(0D, null, null,
                vertex(0D, 0D), vertex(5D, 0D), vertex(10D, 0D));

        assertThatThrownBy(() -> classifier.classify(
                selfIntersecting, new SpatialPoint(1D, 1D, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自相交");
        assertThatThrownBy(() -> classifier.classify(
                duplicate, new SpatialPoint(1D, 1D, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> classifier.classify(
                flat, new SpatialPoint(1D, 1D, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("面积");
    }

    @Test
    void 非有限坐标非法半径和不完整高度必须在构造时拒绝() {
        assertThatThrownBy(() -> new SpatialPoint(Double.NaN, 0D, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限");
        assertThatThrownBy(() -> new SpatialZone.Vertex(0D, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限");
        assertThatThrownBy(() -> new SpatialZone.Circle(
                0D, 0D, 0D, 0D, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("半径");
        assertThatThrownBy(() -> new SpatialZone.Circle(
                0D, 0D, 5D, 0D, 0D, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("高度");
        assertThatThrownBy(() -> new SpatialZone.Polygon(
                Arrays.asList(vertex(0D, 0D), null, vertex(1D, 1D)),
                0D, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("顶点不能为空");
        assertThatThrownBy(() -> polygon(
                1000.1D, null, null,
                vertex(0D, 0D), vertex(1D, 0D), vertex(0D, 1D)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");
    }

    private SpatialZone.Polygon polygon(
            double tolerance,
            Double minZ,
            Double maxZ,
            SpatialZone.Vertex... vertices
    ) {
        return new SpatialZone.Polygon(List.of(vertices), tolerance, minZ, maxZ);
    }

    private SpatialZone.Vertex vertex(double x, double y) {
        return new SpatialZone.Vertex(x, y);
    }
}
