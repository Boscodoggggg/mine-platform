package com.bosco.mine.telemetry.location.transform;

import java.util.Objects;
import java.util.UUID;

/** 基于 WGS84 椭球将经纬高转换为参考原点的东北天坐标。 */
final class Wgs84EnuTransformer implements CoordinateTransformer {

    private static final double SEMI_MAJOR_AXIS = 6_378_137D;
    private static final double FLATTENING = 1D / 298.257223563D;
    private static final double ECCENTRICITY_SQUARED = FLATTENING * (2D - FLATTENING);

    private final UUID transformVersionId;
    private final double originAltitude;
    private final double originEcefX;
    private final double originEcefY;
    private final double originEcefZ;
    private final double sinLongitude;
    private final double cosLongitude;
    private final double sinLatitude;
    private final double cosLatitude;
    private final TargetBounds targetBounds;

    /**
     * 预计算固定参考原点的 ECEF 坐标与旋转系数。
     *
     * @param transformVersionId 转换版本标识
     * @param parameters WGS84 参考原点
     * @param targetBounds 目标坐标边界
     */
    Wgs84EnuTransformer(
            UUID transformVersionId,
            Wgs84EnuParameters parameters,
            TargetBounds targetBounds
    ) {
        this.transformVersionId = Objects.requireNonNull(transformVersionId);
        Objects.requireNonNull(parameters);
        this.originAltitude = parameters.originAltitude();
        double longitudeRadians = Math.toRadians(parameters.originLongitude());
        double latitudeRadians = Math.toRadians(parameters.originLatitude());
        this.sinLongitude = Math.sin(longitudeRadians);
        this.cosLongitude = Math.cos(longitudeRadians);
        this.sinLatitude = Math.sin(latitudeRadians);
        this.cosLatitude = Math.cos(latitudeRadians);
        double[] origin = toEcef(
                longitudeRadians, latitudeRadians, parameters.originAltitude());
        this.originEcefX = origin[0];
        this.originEcefY = origin[1];
        this.originEcefZ = origin[2];
        this.targetBounds = Objects.requireNonNull(targetBounds);
    }

    /** {@inheritDoc} */
    @Override
    public TransformedCoordinate transform(SourceCoordinate source) {
        Objects.requireNonNull(source, "来源坐标不能为空");
        if (source.x() < -180D || source.x() > 180D) {
            throw new IllegalArgumentException("WGS84 来源经度必须位于 -180 至 180 度");
        }
        if (source.y() < -90D || source.y() > 90D) {
            throw new IllegalArgumentException("WGS84 来源纬度必须位于 -90 至 90 度");
        }
        double altitude = source.z() == null ? originAltitude : source.z();
        double[] ecef = toEcef(
                Math.toRadians(source.x()), Math.toRadians(source.y()), altitude);
        double deltaX = ecef[0] - originEcefX;
        double deltaY = ecef[1] - originEcefY;
        double deltaZ = ecef[2] - originEcefZ;

        double east = -sinLongitude * deltaX + cosLongitude * deltaY;
        double north = -sinLatitude * cosLongitude * deltaX
                - sinLatitude * sinLongitude * deltaY + cosLatitude * deltaZ;
        double up = cosLatitude * cosLongitude * deltaX
                + cosLatitude * sinLongitude * deltaY + sinLatitude * deltaZ;
        var result = new TransformedCoordinate(transformVersionId, east, north, up);
        targetBounds.requireContains(result);
        return result;
    }

    private static double[] toEcef(double longitude, double latitude, double altitude) {
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        double primeVerticalRadius = SEMI_MAJOR_AXIS
                / Math.sqrt(1D - ECCENTRICITY_SQUARED * sinLatitude * sinLatitude);
        return new double[]{
                (primeVerticalRadius + altitude) * cosLatitude * Math.cos(longitude),
                (primeVerticalRadius + altitude) * cosLatitude * Math.sin(longitude),
                (primeVerticalRadius * (1D - ECCENTRICITY_SQUARED) + altitude) * sinLatitude
        };
    }
}
