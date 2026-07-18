package com.bosco.mine.model.spatial;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 使用圆形距离和确定性射线法分类矿区统一坐标位置。
 */
public final class SpatialClassifier {

    private static final double EPSILON = 0.000000001D;
    private static final double MIN_POLYGON_AREA = 0.01D;

    /**
     * 分类位置点；区域和位置相同则结果始终相同。
     *
     * @param zone 已发布空间区域
     * @param point 统一坐标位置点
     * @return 固定空间分类结果
     */
    public SpatialClassification classify(SpatialZone zone, SpatialPoint point) {
        Objects.requireNonNull(zone, "空间区域不能为空");
        Objects.requireNonNull(point, "空间位置点不能为空");

        SpatialClassification planar;
        if (zone instanceof SpatialZone.Circle circle) {
            planar = classifyCircle(circle, point);
        } else if (zone instanceof SpatialZone.Polygon polygon) {
            validatePolygon(polygon.vertices());
            planar = classifyPolygon(polygon, point);
        } else {
            throw new IllegalArgumentException("不支持的空间区域类型");
        }
        if (planar == SpatialClassification.OUTSIDE) {
            return planar;
        }
        return applyHeight(zone, point, planar);
    }

    private SpatialClassification classifyCircle(
            SpatialZone.Circle circle,
            SpatialPoint point
    ) {
        double distance = Math.hypot(
                point.x() - circle.centerX(),
                point.y() - circle.centerY());
        if (Math.abs(distance - circle.radiusMeters())
                <= circle.boundaryToleranceMeters() + EPSILON) {
            return SpatialClassification.BOUNDARY;
        }
        return distance < circle.radiusMeters()
                ? SpatialClassification.INSIDE
                : SpatialClassification.OUTSIDE;
    }

    private SpatialClassification classifyPolygon(
            SpatialZone.Polygon polygon,
            SpatialPoint point
    ) {
        List<SpatialZone.Vertex> vertices = polygon.vertices();
        boolean inside = false;
        for (int current = 0, previous = vertices.size() - 1;
             current < vertices.size(); previous = current++) {
            SpatialZone.Vertex first = vertices.get(previous);
            SpatialZone.Vertex second = vertices.get(current);
            if (distanceToSegment(point.x(), point.y(), first, second)
                    <= polygon.boundaryToleranceMeters() + EPSILON) {
                return SpatialClassification.BOUNDARY;
            }
            boolean crossesRay = (first.y() > point.y()) != (second.y() > point.y())
                    && point.x() < (second.x() - first.x())
                    * (point.y() - first.y()) / (second.y() - first.y()) + first.x();
            if (crossesRay) {
                inside = !inside;
            }
        }
        return inside ? SpatialClassification.INSIDE : SpatialClassification.OUTSIDE;
    }

    private SpatialClassification applyHeight(
            SpatialZone zone,
            SpatialPoint point,
            SpatialClassification planar
    ) {
        if (zone.minZ() == null) {
            return planar;
        }
        if (point.z() == null) {
            return SpatialClassification.HEIGHT_UNKNOWN;
        }
        return point.z() >= zone.minZ() && point.z() <= zone.maxZ()
                ? planar : SpatialClassification.OUTSIDE;
    }

    private void validatePolygon(List<SpatialZone.Vertex> vertices) {
        if (new HashSet<>(vertices).size() != vertices.size()) {
            throw new IllegalArgumentException("多边形不得包含重复顶点");
        }
        if (selfIntersects(vertices)) {
            throw new IllegalArgumentException("多边形不得自相交");
        }
        if (polygonArea(vertices) <= MIN_POLYGON_AREA) {
            throw new IllegalArgumentException("多边形面积必须大于 0.01 平方米");
        }
    }

    private double polygonArea(List<SpatialZone.Vertex> vertices) {
        double doubledArea = 0D;
        for (int index = 0; index < vertices.size(); index++) {
            SpatialZone.Vertex current = vertices.get(index);
            SpatialZone.Vertex next = vertices.get((index + 1) % vertices.size());
            doubledArea += current.x() * next.y() - next.x() * current.y();
        }
        return Math.abs(doubledArea) / 2D;
    }

    private boolean selfIntersects(List<SpatialZone.Vertex> vertices) {
        int size = vertices.size();
        for (int first = 0; first < size; first++) {
            SpatialZone.Vertex firstStart = vertices.get(first);
            SpatialZone.Vertex firstEnd = vertices.get((first + 1) % size);
            for (int second = first + 1; second < size; second++) {
                if (second == first + 1 || (first == 0 && second == size - 1)) {
                    continue;
                }
                SpatialZone.Vertex secondStart = vertices.get(second);
                SpatialZone.Vertex secondEnd = vertices.get((second + 1) % size);
                if (segmentsIntersect(firstStart, firstEnd, secondStart, secondEnd)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentsIntersect(
            SpatialZone.Vertex firstStart,
            SpatialZone.Vertex firstEnd,
            SpatialZone.Vertex secondStart,
            SpatialZone.Vertex secondEnd
    ) {
        double firstSideStart = cross(firstStart, firstEnd, secondStart);
        double firstSideEnd = cross(firstStart, firstEnd, secondEnd);
        double secondSideStart = cross(secondStart, secondEnd, firstStart);
        double secondSideEnd = cross(secondStart, secondEnd, firstEnd);

        if (opposite(firstSideStart, firstSideEnd)
                && opposite(secondSideStart, secondSideEnd)) {
            return true;
        }
        return onSegment(firstStart, firstEnd, secondStart, firstSideStart)
                || onSegment(firstStart, firstEnd, secondEnd, firstSideEnd)
                || onSegment(secondStart, secondEnd, firstStart, secondSideStart)
                || onSegment(secondStart, secondEnd, firstEnd, secondSideEnd);
    }

    private boolean opposite(double first, double second) {
        return (first > EPSILON && second < -EPSILON)
                || (first < -EPSILON && second > EPSILON);
    }

    private boolean onSegment(
            SpatialZone.Vertex start,
            SpatialZone.Vertex end,
            SpatialZone.Vertex candidate,
            double cross
    ) {
        return Math.abs(cross) <= EPSILON
                && candidate.x() >= Math.min(start.x(), end.x()) - EPSILON
                && candidate.x() <= Math.max(start.x(), end.x()) + EPSILON
                && candidate.y() >= Math.min(start.y(), end.y()) - EPSILON
                && candidate.y() <= Math.max(start.y(), end.y()) + EPSILON;
    }

    private double cross(
            SpatialZone.Vertex start,
            SpatialZone.Vertex end,
            SpatialZone.Vertex candidate
    ) {
        return (end.x() - start.x()) * (candidate.y() - start.y())
                - (end.y() - start.y()) * (candidate.x() - start.x());
    }

    private double distanceToSegment(
            double x,
            double y,
            SpatialZone.Vertex start,
            SpatialZone.Vertex end
    ) {
        double deltaX = end.x() - start.x();
        double deltaY = end.y() - start.y();
        double lengthSquared = deltaX * deltaX + deltaY * deltaY;
        double projection = ((x - start.x()) * deltaX + (y - start.y()) * deltaY)
                / lengthSquared;
        double bounded = Math.max(0D, Math.min(1D, projection));
        return Math.hypot(
                x - (start.x() + bounded * deltaX),
                y - (start.y() + bounded * deltaY));
    }
}
