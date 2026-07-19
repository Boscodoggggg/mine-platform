package com.bosco.mine.telemetry.spatial;

import com.bosco.mine.model.spatial.SpatialZone;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 将不可变运行快照中的途径点或电子围栏 JSON 编译为统一空间判定配置。
 */
final class SpatialZoneCompiler {

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 编译一个不可变区域发布版本。
     *
     * @param itemType 运行快照条目类型
     * @param contentJson 区域发布版本 JSON
     * @return 已校验的空间区域配置
     */
    CompiledZone compile(String itemType, String contentJson) {
        JsonNode content = readObject(contentJson);
        UUID versionId = requiredUuid(content, "versionId");
        int enterConfirmPoints = requiredInt(content, "enterConfirmPoints");
        int exitConfirmPoints = requiredInt(content, "exitConfirmPoints");
        int dwellSeconds = requiredInt(content, "dwellSeconds");
        double tolerance = requiredDouble(content, "boundaryToleranceMeters");
        Double minZ = optionalDouble(content, "minZ");
        Double maxZ = optionalDouble(content, "maxZ");
        SpatialZone geometry;
        String zoneType;
        UUID zoneId;
        if ("WAYPOINT_VERSION".equals(itemType)) {
            zoneType = "WAYPOINT";
            zoneId = requiredUuid(content, "waypointId");
            geometry = new SpatialZone.Circle(
                    requiredDouble(content, "x"), requiredDouble(content, "y"),
                    requiredDouble(content, "radiusMeters"), tolerance, minZ, maxZ);
        } else if ("GEOFENCE_VERSION".equals(itemType)) {
            zoneType = "GEOFENCE";
            zoneId = requiredUuid(content, "geofenceId");
            List<SpatialZone.Vertex> vertices = new ArrayList<>();
            requiredArray(content, "vertices").stream()
                    .sorted(Comparator.comparingInt(vertex -> requiredInt(
                            vertex, "sequenceNumber")))
                    .forEach(vertex -> vertices.add(new SpatialZone.Vertex(
                            requiredDouble(vertex, "x"), requiredDouble(vertex, "y"))));
            geometry = new SpatialZone.Polygon(vertices, tolerance, minZ, maxZ);
        } else {
            throw new IllegalArgumentException("不支持的空间快照条目类型: " + itemType);
        }
        return new CompiledZone(
                zoneType,
                new SpatialEventStateMachine.ZoneConfiguration(
                        zoneId, versionId, geometry,
                        enterConfirmPoints, exitConfirmPoints, dwellSeconds),
                requiredInstant(content, "validFrom"), optionalInstant(content, "validTo"),
                requiredText(content, "contentHash"));
    }

    private JsonNode readObject(String value) {
        try {
            JsonNode result = jsonMapper.readTree(value);
            if (result == null || !result.isObject()) {
                throw new IllegalArgumentException("空间快照条目内容必须是对象");
            }
            return result;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("空间快照条目 JSON 无法解析", exception);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("空间快照缺少必要文本字段: " + field);
        }
        return value.asString();
    }

    private UUID requiredUuid(JsonNode node, String field) {
        try {
            return UUID.fromString(requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("空间快照 UUID 字段不合法: " + field, exception);
        }
    }

    private Instant requiredInstant(JsonNode node, String field) {
        try {
            return Instant.parse(requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("空间快照时间字段不合法: " + field, exception);
        }
    }

    private Instant optionalInstant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : requiredInstant(node, field);
    }

    private double requiredDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("空间快照数值字段不合法: " + field);
        }
        return value.doubleValue();
    }

    private Double optionalDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : requiredDouble(node, field);
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("空间快照整数字段不合法: " + field);
        }
        return value.intValue();
    }

    private List<JsonNode> requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException("空间快照数组字段不合法: " + field);
        }
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return result;
    }

    /** 已编译且带有效时间和不可变摘要的单个区域版本。 */
    record CompiledZone(
            String zoneType,
            SpatialEventStateMachine.ZoneConfiguration configuration,
            Instant validFrom,
            Instant validTo,
            String contentHash
    ) {

        /**
         * 判断位置事实是否落入区域版本有效时间窗。
         *
         * @param occurredAt 位置事实发生时间
         * @return 是否应采用本区域版本判定
         */
        boolean contains(Instant occurredAt) {
            return !occurredAt.isBefore(validFrom)
                    && (validTo == null || occurredAt.isBefore(validTo));
        }
    }
}
