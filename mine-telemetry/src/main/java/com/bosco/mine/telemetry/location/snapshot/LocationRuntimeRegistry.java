package com.bosco.mine.telemetry.location.snapshot;

import com.bosco.mine.model.spatial.SpatialClassifier;
import com.bosco.mine.model.spatial.SpatialPoint;
import com.bosco.mine.model.spatial.SpatialZone;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformRegistry;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 持久暂存定位快照、原子推进最后正确修订并提供无 IO 的运行时读取。
 */
public final class LocationRuntimeRegistry {

    private static final Pattern HASH = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern ITEM_KEY =
            Pattern.compile("^[A-Z][A-Z_]*:[0-9a-f-]{36}$");
    private static final Map<String, String> ITEM_PREFIXES = Map.ofEntries(
            Map.entry("SITE", "SITE:"),
            Map.entry("VEHICLE", "VEHICLE:"),
            Map.entry("PERSON", "PERSON:"),
            Map.entry("LOCATION_TAG", "LOCATION_TAG:"),
            Map.entry("SOURCE_DEVICE", "SOURCE_DEVICE:"),
            Map.entry("COORDINATE_SYSTEM", "COORDINATE_SYSTEM:"),
            Map.entry("COORDINATE_TRANSFORM", "COORDINATE_TRANSFORM:"),
            Map.entry("VEHICLE_DEVICE_BINDING", "VEHICLE_DEVICE_BINDING:"),
            Map.entry("VEHICLE_TAG_BINDING", "VEHICLE_TAG_BINDING:"),
            Map.entry("PERSON_TAG_BINDING", "PERSON_TAG_BINDING:"),
            Map.entry("PERSON_VEHICLE_BINDING", "PERSON_VEHICLE_BINDING:"),
            Map.entry("WAYPOINT_VERSION", "WAYPOINT_VERSION:"),
            Map.entry("ROUTE_VERSION", "ROUTE_VERSION:"),
            Map.entry("GEOFENCE_VERSION", "GEOFENCE_VERSION:")
    );
    private static final Set<String> WAYPOINT_TYPES =
            Set.of("LOAD", "UNLOAD", "CHECK", "WAIT", "ENTRY", "EXIT");
    private static final Set<String> ROUTE_DIRECTIONS =
            Set.of("FORWARD", "REVERSE", "BIDIRECTIONAL");
    private static final Set<String> GEOFENCE_TYPES =
            Set.of("RESTRICTED", "WORK", "LOAD", "UNLOAD", "WAIT", "SAFETY", "OTHER");
    private static final Set<String> VEHICLE_TYPES = Set.of(
            "HAUL_TRUCK", "EXCAVATOR", "LOADER", "DRILL", "DOZER",
            "WATER_TRUCK", "LIGHT_VEHICLE", "OTHER");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CoordinateTransformRegistry transformRegistry;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final ConcurrentHashMap<UUID, RuntimeSnapshot> runtimes = new ConcurrentHashMap<>();

    /**
     * 创建 TimescaleDB 持久镜像与进程内只读注册表。
     *
     * @param dataSource Telemetry 时序数据库
     * @param transformRegistry 坐标转换参数独立校验器
     */
    public LocationRuntimeRegistry(
            DataSource dataSource,
            CoordinateTransformRegistry transformRegistry
    ) {
        Objects.requireNonNull(dataSource, "定位快照数据源不能为空");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.transformRegistry = Objects.requireNonNull(
                transformRegistry, "坐标转换注册表不能为空");
    }

    /**
     * 幂等暂存一页已经完成规范化和摘要校验的快照条目。
     *
     * @param metadata 完整快照元数据
     * @param items 当前页条目
     */
    public void stage(SnapshotMetadata metadata, List<StagedItem> items) {
        Objects.requireNonNull(metadata, "定位快照元数据不能为空");
        Objects.requireNonNull(items, "定位快照暂存条目不能为空");
        if (items.isEmpty()) {
            return;
        }
        int[][] results = jdbcTemplate.batchUpdate("""
                INSERT INTO location_snapshot_stage (
                    snapshot_id, organization_id, site_id, runtime_revision,
                    item_key, item_type, content_json, content_hash,
                    expected_item_count, snapshot_content_hash,
                    source_created_at, staged_at
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (snapshot_id, item_key) DO UPDATE SET
                    staged_at = CURRENT_TIMESTAMP
                WHERE location_snapshot_stage.organization_id = EXCLUDED.organization_id
                  AND location_snapshot_stage.site_id = EXCLUDED.site_id
                  AND location_snapshot_stage.runtime_revision = EXCLUDED.runtime_revision
                  AND location_snapshot_stage.item_type = EXCLUDED.item_type
                  AND location_snapshot_stage.content_json = EXCLUDED.content_json
                  AND location_snapshot_stage.content_hash = EXCLUDED.content_hash
                  AND location_snapshot_stage.expected_item_count = EXCLUDED.expected_item_count
                  AND location_snapshot_stage.snapshot_content_hash = EXCLUDED.snapshot_content_hash
                  AND location_snapshot_stage.source_created_at = EXCLUDED.source_created_at
                """, items, items.size(),
                (PreparedStatement statement, StagedItem item) -> {
                    statement.setObject(1, metadata.snapshotId());
                    statement.setObject(2, metadata.organizationId());
                    statement.setObject(3, metadata.siteId());
                    statement.setLong(4, metadata.revision());
                    statement.setString(5, item.itemKey());
                    statement.setString(6, item.itemType());
                    statement.setString(7, item.contentJson());
                    statement.setString(8, item.contentHash());
                    statement.setInt(9, metadata.itemCount());
                    statement.setString(10, metadata.contentHash());
                    statement.setTimestamp(11, java.sql.Timestamp.from(metadata.createdAt()));
                });
        for (int[] batch : results) {
            for (int result : batch) {
                if (result == 0) {
                    throw new PermanentLocationSnapshotException(
                            "相同定位快照条目对应了不同内容");
                }
            }
        }
    }

    /**
     * 删除未激活快照的暂存条目，永不删除当前最后正确版本。
     *
     * @param snapshotId 快照标识
     */
    public void discard(UUID snapshotId) {
        jdbcTemplate.update("""
                DELETE FROM location_snapshot_stage stage
                WHERE stage.snapshot_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM location_runtime_revision runtime
                      WHERE runtime.snapshot_id = stage.snapshot_id
                  )
                """, snapshotId);
    }

    /**
     * 在单事务内复核整包、推进修订并切换进程内最后正确快照。
     *
     * @param metadata 已通过传输完整性校验的快照元数据
     * @return 是否应用了更高修订
     */
    public boolean activate(SnapshotMetadata metadata) {
        Activation activation = transactionTemplate.execute(status -> {
            lockSite(metadata.siteId());
            CurrentRevision current = currentRevision(metadata.siteId());
            if (current != null && current.revision() > metadata.revision()) {
                discard(metadata.snapshotId());
                return new Activation(false, null);
            }
            if (current != null && current.revision() == metadata.revision()
                    && (!current.snapshotId().equals(metadata.snapshotId())
                    || !current.contentHash().equals(metadata.contentHash())
                    || current.itemCount() != metadata.itemCount())) {
                throw new PermanentLocationSnapshotException(
                        "相同修订号对应了不同定位快照声明");
            }

            RuntimeSnapshot runtime = buildRuntime(metadata, loadItems(metadata.snapshotId()));
            if (current == null || current.revision() < metadata.revision()) {
                jdbcTemplate.update("""
                        INSERT INTO location_runtime_revision (
                            site_id, organization_id, runtime_revision, snapshot_id,
                            item_count, content_hash, source_created_at, applied_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT (site_id) DO UPDATE SET
                            organization_id = EXCLUDED.organization_id,
                            runtime_revision = EXCLUDED.runtime_revision,
                            snapshot_id = EXCLUDED.snapshot_id,
                            item_count = EXCLUDED.item_count,
                            content_hash = EXCLUDED.content_hash,
                            source_created_at = EXCLUDED.source_created_at,
                            applied_at = CURRENT_TIMESTAMP
                        WHERE location_runtime_revision.runtime_revision
                              < EXCLUDED.runtime_revision
                        """, metadata.siteId(), metadata.organizationId(), metadata.revision(),
                        metadata.snapshotId(), metadata.itemCount(), metadata.contentHash(),
                        java.sql.Timestamp.from(metadata.createdAt()));
                jdbcTemplate.update("""
                        DELETE FROM location_snapshot_stage
                        WHERE site_id = ? AND snapshot_id <> ?
                          AND runtime_revision <= ?
                        """, metadata.siteId(), metadata.snapshotId(), metadata.revision());
                return new Activation(true, runtime);
            }
            return new Activation(false, runtime);
        });
        if (activation != null && activation.runtime() != null) {
            runtimes.put(metadata.siteId(), activation.runtime());
        }
        return activation != null && activation.applied();
    }

    /**
     * 从已激活持久修订恢复全部矿区内存镜像；任一快照损坏时不部分切换。
     */
    public void restore() {
        List<SnapshotMetadata> metadata = jdbcTemplate.query("""
                SELECT snapshot_id, organization_id, site_id, runtime_revision,
                       item_count, content_hash, source_created_at
                FROM location_runtime_revision
                ORDER BY site_id
                """, (resultSet, rowNumber) -> new SnapshotMetadata(
                resultSet.getObject("snapshot_id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getLong("runtime_revision"),
                resultSet.getInt("item_count"),
                resultSet.getString("content_hash"),
                resultSet.getTimestamp("source_created_at").toInstant()));
        Map<UUID, RuntimeSnapshot> restored = new LinkedHashMap<>();
        for (SnapshotMetadata item : metadata) {
            restored.put(item.siteId(), buildRuntime(item, loadItems(item.snapshotId())));
        }
        runtimes.clear();
        runtimes.putAll(restored);
    }

    /**
     * 读取矿区当前最后正确运行快照。
     *
     * @param siteId 矿区标识
     * @return 当前快照，不存在时为空
     */
    public Optional<RuntimeSnapshot> current(UUID siteId) {
        return Optional.ofNullable(runtimes.get(siteId));
    }

    /**
     * 按可信来源设备定位当前矿区运行快照。
     *
     * @param sourceDeviceId 来源设备标识
     * @return 包含该来源设备的当前快照
     */
    public Optional<RuntimeSnapshot> currentForSourceDevice(UUID sourceDeviceId) {
        Objects.requireNonNull(sourceDeviceId, "来源设备标识不能为空");
        String itemKey = "SOURCE_DEVICE:" + sourceDeviceId;
        List<RuntimeSnapshot> matches = runtimes.values().stream()
                .filter(snapshot -> snapshot.items().containsKey(itemKey))
                .limit(2)
                .toList();
        if (matches.size() > 1) {
            throw new PermanentLocationSnapshotException(
                    "来源设备同时存在于多个激活矿区快照");
        }
        return matches.stream().findFirst();
    }

    /**
     * 判断进程是否至少恢复或激活了一个矿区快照。
     *
     * @return 存在任意最后正确矿区快照时为真
     */
    public boolean hasActiveSnapshots() {
        return !runtimes.isEmpty();
    }

    private RuntimeSnapshot buildRuntime(
            SnapshotMetadata metadata,
            List<RuntimeItem> items
    ) {
        if (items.size() != metadata.itemCount()) {
            throw new PermanentLocationSnapshotException("定位快照暂存条目数量不完整");
        }
        Map<String, RuntimeItem> byKey = new TreeMap<>();
        StringBuilder digestInput = new StringBuilder(items.size() * 120);
        for (RuntimeItem item : items.stream()
                .sorted(Comparator.comparing(RuntimeItem::itemKey)).toList()) {
            validateItemIdentity(item);
            String canonicalJson = canonicalJson(readJson(item.contentJson()));
            if (!hash(canonicalJson).equals(item.contentHash())) {
                throw new PermanentLocationSnapshotException("持久定位快照条目摘要不一致");
            }
            if (byKey.put(item.itemKey(), new RuntimeItem(
                    item.itemKey(), item.itemType(), canonicalJson, item.contentHash())) != null) {
                throw new PermanentLocationSnapshotException("定位快照条目键重复");
            }
            digestInput.append(item.itemKey()).append(':')
                    .append(item.contentHash()).append('\n');
        }
        if (!hash(digestInput.toString()).equals(metadata.contentHash())) {
            throw new PermanentLocationSnapshotException("持久定位快照完整摘要不一致");
        }
        validateSite(metadata, byKey);
        CoordinateSystem unifiedCoordinate = validateCoordinateTransforms(byKey);
        validateSpatialVersions(byKey, unifiedCoordinate);
        return new RuntimeSnapshot(
                metadata.snapshotId(), metadata.organizationId(), metadata.siteId(),
                metadata.revision(), metadata.itemCount(), metadata.contentHash(),
                metadata.createdAt(), Map.copyOf(byKey));
    }

    private void validateSite(SnapshotMetadata metadata, Map<String, RuntimeItem> items) {
        RuntimeItem site = items.get("SITE:" + metadata.siteId());
        if (site == null) {
            throw new PermanentLocationSnapshotException("定位快照缺少矿区基础条目");
        }
        JsonNode content = readJson(site.contentJson());
        if (!metadata.organizationId().equals(requiredUuid(content, "organizationId"))
                || !metadata.siteId().equals(requiredUuid(content, "siteId"))
                || !"ACTIVE".equals(requiredText(content, "status"))) {
            throw new PermanentLocationSnapshotException("定位快照矿区基础条目不一致");
        }
    }

    /** 校验坐标转换并返回唯一活动统一坐标系及其边界。 */
    private CoordinateSystem validateCoordinateTransforms(Map<String, RuntimeItem> items) {
        Map<UUID, CoordinateSystem> systems = new LinkedHashMap<>();
        int activeUnifiedCount = 0;
        for (RuntimeItem item : items.values()) {
            if (!"COORDINATE_SYSTEM".equals(item.itemType())) {
                continue;
            }
            JsonNode content = readJson(item.contentJson());
            UUID id = requiredUuid(content, "coordinateSystemId");
            String status = requiredText(content, "status");
            boolean unified = requiredBoolean(content, "unified");
            CoordinateTransformer.TargetBounds bounds = new CoordinateTransformer.TargetBounds(
                    requiredDouble(content, "minX"), requiredDouble(content, "maxX"),
                    requiredDouble(content, "minY"), requiredDouble(content, "maxY"),
                    optionalDouble(content, "minZ"), optionalDouble(content, "maxZ"));
            if (systems.put(id, new CoordinateSystem(id, status, unified, bounds)) != null) {
                throw new PermanentLocationSnapshotException("定位快照坐标系标识重复");
            }
            if (unified && "ACTIVE".equals(status)) {
                activeUnifiedCount++;
                if (!"LOCAL_CARTESIAN".equals(requiredText(content, "coordinateType"))
                        || !"METER".equals(requiredText(content, "unit"))) {
                    throw new PermanentLocationSnapshotException(
                            "统一矿区坐标系必须是米制本地笛卡尔坐标");
                }
            }
        }
        if (activeUnifiedCount != 1) {
            throw new PermanentLocationSnapshotException("定位快照必须包含唯一有效统一坐标系");
        }

        for (RuntimeItem item : items.values()) {
            if (!"COORDINATE_TRANSFORM".equals(item.itemType())) {
                continue;
            }
            try {
                JsonNode content = readJson(item.contentJson());
                UUID transformId = requiredUuid(content, "coordinateTransformId");
                UUID sourceId = requiredUuid(content, "sourceCoordinateSystemId");
                UUID targetId = requiredUuid(content, "targetCoordinateSystemId");
                CoordinateSystem source = systems.get(sourceId);
                CoordinateSystem target = systems.get(targetId);
                if (source == null || target == null
                        || !"ACTIVE".equals(source.status())
                        || !"ACTIVE".equals(target.status())) {
                    throw new PermanentLocationSnapshotException(
                            "坐标转换引用了缺失或失效坐标系");
                }
                CoordinateTransformer.Algorithm algorithm = CoordinateTransformer.Algorithm
                        .valueOf(requiredText(content, "algorithm"));
                JsonNode parameters = requiredObject(content, "parameters");
                CoordinateTransformer.TransformParameters typedParameters = switch (algorithm) {
                    case IDENTITY -> {
                        if (!parameters.isEmpty()) {
                            throw new IllegalArgumentException("恒等转换参数必须为空");
                        }
                        yield new CoordinateTransformer.IdentityParameters();
                    }
                    case AFFINE_3D -> new CoordinateTransformer.Affine3dParameters(
                            requiredDoubleArray(parameters, "matrix", 16));
                    case WGS84_ENU -> new CoordinateTransformer.Wgs84EnuParameters(
                            requiredDouble(parameters, "originLongitude"),
                            requiredDouble(parameters, "originLatitude"),
                            requiredDouble(parameters, "originAltitude"));
                };
                transformRegistry.resolve(new CoordinateTransformer.TransformSnapshot(
                        transformId, algorithm, typedParameters, target.bounds()));
            } catch (PermanentLocationSnapshotException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new PermanentLocationSnapshotException(
                        "定位快照坐标转换参数不合法", exception);
            }
        }
        return systems.values().stream()
                .filter(system -> system.unified() && "ACTIVE".equals(system.status()))
                .findFirst()
                .orElseThrow(() -> new PermanentLocationSnapshotException(
                        "定位快照缺少活动统一坐标系"));
    }

    /** 校验空间版本均可被运行时安全编译，并验证路线引用完整性。 */
    private void validateSpatialVersions(
            Map<String, RuntimeItem> items,
            CoordinateSystem unifiedCoordinate
    ) {
        UUID unifiedCoordinateId = unifiedCoordinate.id();
        Map<UUID, SpatialVersion> waypoints = new LinkedHashMap<>();
        List<PublishedInterval> publishedIntervals = new ArrayList<>();
        SpatialClassifier classifier = new SpatialClassifier();

        for (RuntimeItem item : items.values()) {
            if (!"WAYPOINT_VERSION".equals(item.itemType())) {
                continue;
            }
            try {
                JsonNode content = readJson(item.contentJson());
                UUID versionId = requireVersionIdentity(item, content);
                UUID waypointId = requiredUuid(content, "waypointId");
                UUID coordinateId = requiredUuid(content, "coordinateSystemId");
                requireUnifiedCoordinate(coordinateId, unifiedCoordinateId);
                if (requiredInt(content, "versionNumber") < 1
                        || !WAYPOINT_TYPES.contains(requiredText(content, "waypointType"))) {
                    throw new IllegalArgumentException("途径点版本枚举或版本号不合法");
                }
                validatePolicy(content);
                double x = requiredDouble(content, "x");
                double y = requiredDouble(content, "y");
                Double z = optionalDouble(content, "z");
                Double minZ = optionalDouble(content, "minZ");
                Double maxZ = optionalDouble(content, "maxZ");
                requireSpatialBounds(unifiedCoordinate.bounds(), x, y, z);
                requireHeightBounds(unifiedCoordinate.bounds(), minZ, maxZ);
                SpatialZone.Circle circle = new SpatialZone.Circle(
                        x, y,
                        requiredDouble(content, "radiusMeters"),
                        requiredDouble(content, "boundaryToleranceMeters"),
                        minZ, maxZ);
                classifier.classify(circle, new SpatialPoint(circle.centerX(), circle.centerY(), null));
                Instant validFrom = requiredInstant(content, "validFrom");
                Instant validTo = optionalInstant(content, "validTo");
                validateValidity(validFrom, validTo);
                requirePublishedHash(content);
                waypoints.put(versionId, new SpatialVersion(
                        waypointId, coordinateId, validFrom, validTo));
                publishedIntervals.add(new PublishedInterval(
                        "WAYPOINT", waypointId, validFrom, validTo));
            } catch (PermanentLocationSnapshotException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new PermanentLocationSnapshotException("定位快照途径点版本不合法", exception);
            }
        }

        for (RuntimeItem item : items.values()) {
            if ("ROUTE_VERSION".equals(item.itemType())) {
                publishedIntervals.add(validateRouteVersion(
                        item, waypoints, unifiedCoordinateId));
            } else if ("GEOFENCE_VERSION".equals(item.itemType())) {
                publishedIntervals.add(validateGeofenceVersion(
                        item, classifier, unifiedCoordinate));
            }
        }
        validateNoOverlappingIntervals(publishedIntervals);
    }

    /** 校验单个路线版本的枚举、有效期、有序引用和途径点覆盖关系。 */
    private PublishedInterval validateRouteVersion(
            RuntimeItem item,
            Map<UUID, SpatialVersion> waypoints,
            UUID unifiedCoordinateId
    ) {
        try {
            JsonNode content = readJson(item.contentJson());
            requireVersionIdentity(item, content);
            UUID routeId = requiredUuid(content, "routeId");
            UUID coordinateId = requiredUuid(content, "coordinateSystemId");
            requireUnifiedCoordinate(coordinateId, unifiedCoordinateId);
            if (requiredInt(content, "versionNumber") < 1
                    || !ROUTE_DIRECTIONS.contains(requiredText(content, "direction"))) {
                throw new IllegalArgumentException("路线版本枚举或版本号不合法");
            }
            JsonNode vehicleTypes = requiredArray(content, "applicableVehicleTypes");
            if (vehicleTypes.isEmpty() || vehicleTypes.size() > 16) {
                throw new IllegalArgumentException("路线适用车辆类型数量不合法");
            }
            for (JsonNode vehicleType : vehicleTypes) {
                if (!vehicleType.isString() || !VEHICLE_TYPES.contains(vehicleType.asString())) {
                    throw new IllegalArgumentException("路线适用车辆类型不合法");
                }
            }
            Instant validFrom = requiredInstant(content, "validFrom");
            Instant validTo = optionalInstant(content, "validTo");
            validateValidity(validFrom, validTo);
            requirePublishedHash(content);
            JsonNode references = requiredArray(content, "waypoints");
            if (references.size() < 2 || references.size() > 10_000) {
                throw new IllegalArgumentException("路线途径点数量不合法");
            }
            for (int index = 0; index < references.size(); index++) {
                JsonNode reference = references.get(index);
                if (requiredInt(reference, "sequenceNumber") != index + 1) {
                    throw new IllegalArgumentException("路线途径点顺序不连续");
                }
                requiredBoolean(reference, "stopRequired");
                optionalBoundedInt(reference, "expectedDwellSeconds", 0, 86_400);
                UUID waypointVersionId = requiredUuid(reference, "waypointVersionId");
                SpatialVersion waypoint = waypoints.get(waypointVersionId);
                if (waypoint == null || !coordinateId.equals(waypoint.coordinateSystemId())
                        || waypoint.validFrom().isAfter(validFrom)
                        || !coversEnd(waypoint.validTo(), validTo)) {
                    throw new IllegalArgumentException("路线引用的途径点版本不完整或有效期不覆盖");
                }
            }
            return new PublishedInterval("ROUTE", routeId, validFrom, validTo);
        } catch (PermanentLocationSnapshotException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PermanentLocationSnapshotException("定位快照路线版本不合法", exception);
        }
    }

    /** 校验单个电子围栏版本的策略、有序顶点和简单多边形拓扑。 */
    private PublishedInterval validateGeofenceVersion(
            RuntimeItem item,
            SpatialClassifier classifier,
            CoordinateSystem unifiedCoordinate
    ) {
        try {
            JsonNode content = readJson(item.contentJson());
            requireVersionIdentity(item, content);
            UUID geofenceId = requiredUuid(content, "geofenceId");
            requireUnifiedCoordinate(
                    requiredUuid(content, "coordinateSystemId"), unifiedCoordinate.id());
            if (requiredInt(content, "versionNumber") < 1
                    || !GEOFENCE_TYPES.contains(requiredText(content, "geofenceType"))) {
                throw new IllegalArgumentException("围栏版本枚举或版本号不合法");
            }
            validatePolicy(content);
            Instant validFrom = requiredInstant(content, "validFrom");
            Instant validTo = optionalInstant(content, "validTo");
            validateValidity(validFrom, validTo);
            requirePublishedHash(content);
            JsonNode verticesNode = requiredArray(content, "vertices");
            if (verticesNode.size() < 3 || verticesNode.size() > 10_000) {
                throw new IllegalArgumentException("围栏顶点数量不合法");
            }
            List<SpatialZone.Vertex> vertices = new ArrayList<>(verticesNode.size());
            for (int index = 0; index < verticesNode.size(); index++) {
                JsonNode vertex = verticesNode.get(index);
                if (requiredInt(vertex, "sequenceNumber") != index + 1) {
                    throw new IllegalArgumentException("围栏顶点顺序不连续");
                }
                double x = requiredDouble(vertex, "x");
                double y = requiredDouble(vertex, "y");
                requireSpatialBounds(unifiedCoordinate.bounds(), x, y, null);
                vertices.add(new SpatialZone.Vertex(x, y));
            }
            Double minZ = optionalDouble(content, "minZ");
            Double maxZ = optionalDouble(content, "maxZ");
            requireHeightBounds(unifiedCoordinate.bounds(), minZ, maxZ);
            SpatialZone.Polygon polygon = new SpatialZone.Polygon(
                    vertices, requiredDouble(content, "boundaryToleranceMeters"),
                    minZ, maxZ);
            classifier.classify(polygon, new SpatialPoint(
                    vertices.getFirst().x(), vertices.getFirst().y(), null));
            return new PublishedInterval("GEOFENCE", geofenceId, validFrom, validTo);
        } catch (PermanentLocationSnapshotException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PermanentLocationSnapshotException("定位快照电子围栏版本不合法", exception);
        }
    }

    /** 校验空间内容中的版本标识与稳定条目键一致。 */
    private UUID requireVersionIdentity(RuntimeItem item, JsonNode content) {
        UUID versionId = requiredUuid(content, "versionId");
        UUID keyId = UUID.fromString(item.itemKey().substring(item.itemKey().indexOf(':') + 1));
        if (!versionId.equals(keyId)) {
            throw new PermanentLocationSnapshotException("空间版本标识与条目键不一致");
        }
        return versionId;
    }

    /** 要求空间版本使用当前矿区活动统一坐标系。 */
    private void requireUnifiedCoordinate(UUID coordinateId, UUID unifiedCoordinateId) {
        if (!unifiedCoordinateId.equals(coordinateId)) {
            throw new PermanentLocationSnapshotException("空间版本未使用活动统一坐标系");
        }
    }

    /** 校验空间点位于统一坐标系二维边界内，并在有高程时校验三维边界。 */
    private void requireSpatialBounds(
            CoordinateTransformer.TargetBounds bounds,
            double x,
            double y,
            Double z
    ) {
        boolean outside = x < bounds.minX() || x > bounds.maxX()
                || y < bounds.minY() || y > bounds.maxY();
        if (z != null && bounds.minZ() != null) {
            outside = outside || z < bounds.minZ() || z > bounds.maxZ();
        }
        if (outside) {
            throw new PermanentLocationSnapshotException("空间版本坐标超出统一坐标边界");
        }
    }

    /** 校验可选空间高度区间没有超出统一坐标系高程边界。 */
    private void requireHeightBounds(
            CoordinateTransformer.TargetBounds bounds,
            Double minZ,
            Double maxZ
    ) {
        if (minZ != null && maxZ != null && bounds.minZ() != null
                && (minZ < bounds.minZ() || maxZ > bounds.maxZ())) {
            throw new PermanentLocationSnapshotException("空间版本高度超出统一坐标边界");
        }
    }

    /**
     * 禁止同一类稳定空间对象的发布版本有效期重叠；相邻半开区间允许首尾衔接。
     */
    private void validateNoOverlappingIntervals(List<PublishedInterval> intervals) {
        Map<SpatialObjectKey, List<PublishedInterval>> grouped = new LinkedHashMap<>();
        for (PublishedInterval interval : intervals) {
            SpatialObjectKey key = new SpatialObjectKey(
                    interval.objectType(), interval.objectId());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(interval);
        }
        for (List<PublishedInterval> versions : grouped.values()) {
            versions.sort(Comparator.comparing(PublishedInterval::validFrom));
            Instant previousEnd = null;
            boolean previousOpenEnded = false;
            for (PublishedInterval version : versions) {
                if (previousOpenEnded
                        || previousEnd != null && version.validFrom().isBefore(previousEnd)) {
                    throw new PermanentLocationSnapshotException(
                            "同一空间稳定对象的发布有效期重叠");
                }
                previousEnd = version.validTo();
                previousOpenEnded = previousEnd == null;
            }
        }
    }

    /** 校验控制面发布版本携带合法内容摘要。 */
    private void requirePublishedHash(JsonNode content) {
        if (!HASH.matcher(requiredText(content, "contentHash")).matches()) {
            throw new PermanentLocationSnapshotException("空间发布版本摘要不合法");
        }
    }

    /** 校验空间消抖、停留和迟到水位参数的运行边界。 */
    private void validatePolicy(JsonNode content) {
        double tolerance = requiredDouble(content, "boundaryToleranceMeters");
        int enter = requiredInt(content, "enterConfirmPoints");
        int exit = requiredInt(content, "exitConfirmPoints");
        int dwell = requiredInt(content, "dwellSeconds");
        int lateness = requiredInt(content, "allowedLatenessSeconds");
        int idle = requiredInt(content, "idleFlushSeconds");
        if (tolerance < 0D || tolerance > 1_000D
                || enter < 1 || enter > 20 || exit < 1 || exit > 20
                || dwell < 0 || dwell > 86_400 || lateness < 0 || lateness > 300
                || idle < 1 || idle > 3_600 || idle <= lateness) {
            throw new PermanentLocationSnapshotException("空间判定策略不合法");
        }
    }

    /** 校验空间版本的半开有效时间区间。 */
    private void validateValidity(Instant validFrom, Instant validTo) {
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new PermanentLocationSnapshotException("空间版本有效期不合法");
        }
    }

    /** 判断途径点有效期终点是否覆盖路线有效期终点。 */
    private boolean coversEnd(Instant referenceEnd, Instant routeEnd) {
        return routeEnd == null ? referenceEnd == null
                : referenceEnd == null || !referenceEnd.isBefore(routeEnd);
    }

    private void validateItemIdentity(RuntimeItem item) {
        String prefix = ITEM_PREFIXES.get(item.itemType());
        if (prefix == null || item.itemKey() == null || !ITEM_KEY.matcher(item.itemKey()).matches()
                || !item.itemKey().startsWith(prefix)
                || item.contentHash() == null || !HASH.matcher(item.contentHash()).matches()) {
            throw new PermanentLocationSnapshotException("定位快照条目标识不合法");
        }
    }

    private List<RuntimeItem> loadItems(UUID snapshotId) {
        return jdbcTemplate.query("""
                SELECT item_key, item_type, content_json::TEXT, content_hash
                FROM location_snapshot_stage
                WHERE snapshot_id = ?
                ORDER BY item_key
                """, (resultSet, rowNumber) -> new RuntimeItem(
                resultSet.getString("item_key"), resultSet.getString("item_type"),
                resultSet.getString("content_json"), resultSet.getString("content_hash")),
                snapshotId);
    }

    private CurrentRevision currentRevision(UUID siteId) {
        List<CurrentRevision> rows = jdbcTemplate.query("""
                SELECT runtime_revision, snapshot_id, item_count, content_hash
                FROM location_runtime_revision
                WHERE site_id = ?
                """, (resultSet, rowNumber) -> new CurrentRevision(
                resultSet.getLong("runtime_revision"),
                resultSet.getObject("snapshot_id", UUID.class),
                resultSet.getInt("item_count"), resultSet.getString("content_hash")), siteId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void lockSite(UUID siteId) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> { }, "location-runtime:" + siteId);
    }

    private JsonNode readJson(String value) {
        try {
            JsonNode node = jsonMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new PermanentLocationSnapshotException("定位快照条目内容必须是对象");
            }
            return node;
        } catch (JacksonException exception) {
            throw new PermanentLocationSnapshotException("定位快照条目 JSON 无法解析", exception);
        }
    }

    private String canonicalJson(JsonNode node) {
        try {
            Object value = jsonMapper.treeToValue(node, Object.class);
            return jsonMapper.writeValueAsString(canonicalize(value));
        } catch (JacksonException exception) {
            throw new PermanentLocationSnapshotException("定位快照条目无法规范化", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new PermanentLocationSnapshotException("定位快照缺少必要文本字段");
        }
        return value.asString();
    }

    private UUID requiredUuid(JsonNode node, String field) {
        try {
            return UUID.fromString(requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw new PermanentLocationSnapshotException("定位快照 UUID 字段不合法", exception);
        }
    }

    private double requiredDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new PermanentLocationSnapshotException("定位快照数值字段不合法");
        }
        return value.doubleValue();
    }

    private Double optionalDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new PermanentLocationSnapshotException("定位快照可选数值字段不合法");
        }
        return value.doubleValue();
    }

    /** 读取必填整数，拒绝浮点数或缺失值。 */
    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new PermanentLocationSnapshotException("定位快照整数字段不合法");
        }
        return value.intValue();
    }

    /** 读取限定范围的可选整数。 */
    private Integer optionalBoundedInt(JsonNode node, String field, int minimum, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        int result = requiredInt(node, field);
        if (result < minimum || result > maximum) {
            throw new PermanentLocationSnapshotException("定位快照可选整数字段不合法");
        }
        return result;
    }

    /** 读取必填 UTC 时间点。 */
    private Instant requiredInstant(JsonNode node, String field) {
        try {
            return Instant.parse(requiredText(node, field));
        } catch (RuntimeException exception) {
            throw new PermanentLocationSnapshotException("定位快照时间字段不合法", exception);
        }
    }

    /** 读取允许缺失或为空的时间点。 */
    private Instant optionalInstant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return requiredInstant(node, field);
    }

    private boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new PermanentLocationSnapshotException("定位快照布尔字段不合法");
        }
        return value.asBoolean();
    }

    private JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new PermanentLocationSnapshotException("定位快照对象字段不合法");
        }
        return value;
    }

    /** 读取必填 JSON 数组。 */
    private JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new PermanentLocationSnapshotException("定位快照数组字段不合法");
        }
        return value;
    }

    private List<Double> requiredDoubleArray(JsonNode node, String field, int length) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() != length) {
            throw new PermanentLocationSnapshotException("定位快照矩阵字段长度不合法");
        }
        List<Double> values = new ArrayList<>(length);
        for (JsonNode element : value) {
            if (!element.isNumber() || !Double.isFinite(element.doubleValue())) {
                throw new PermanentLocationSnapshotException("定位快照矩阵包含非有限值");
            }
            values.add(element.doubleValue());
        }
        return List.copyOf(values);
    }

    private String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", exception);
        }
    }

    /**
     * 快照不可变元数据。
     *
     * @param snapshotId 快照标识
     * @param organizationId 客户组织标识
     * @param siteId 矿区标识
     * @param revision 矿区单调修订号
     * @param itemCount 条目数量
     * @param contentHash 完整快照摘要
     * @param createdAt 控制面创建时间
     */
    public record SnapshotMetadata(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            int itemCount,
            String contentHash,
            Instant createdAt
    ) {

        /** 校验快照头不变量。 */
        public SnapshotMetadata {
            Objects.requireNonNull(snapshotId, "快照标识不能为空");
            Objects.requireNonNull(organizationId, "组织标识不能为空");
            Objects.requireNonNull(siteId, "矿区标识不能为空");
            Objects.requireNonNull(createdAt, "快照创建时间不能为空");
            if (revision < 1 || itemCount < 1
                    || contentHash == null || !HASH.matcher(contentHash).matches()) {
                throw new PermanentLocationSnapshotException("定位快照头不合法");
            }
        }
    }

    /**
     * 已独立规范化的暂存条目。
     *
     * @param itemKey 稳定条目键
     * @param itemType 固定条目类型
     * @param contentJson 规范化 JSON
     * @param contentHash 内容摘要
     */
    public record StagedItem(
            String itemKey,
            String itemType,
            String contentJson,
            String contentHash
    ) {
    }

    /**
     * 热路径使用的最后正确矿区运行快照。
     *
     * @param snapshotId 快照标识
     * @param organizationId 客户组织标识
     * @param siteId 矿区标识
     * @param revision 矿区修订号
     * @param itemCount 条目数量
     * @param contentHash 完整快照摘要
     * @param createdAt 控制面创建时间
     * @param items 以稳定条目键索引的不可变条目
     */
    public record RuntimeSnapshot(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            int itemCount,
            String contentHash,
            Instant createdAt,
            Map<String, RuntimeItem> items
    ) {

        /** 冻结运行快照条目索引。 */
        public RuntimeSnapshot {
            items = Map.copyOf(items);
        }
    }

    /** 路线引用校验所需的最小途径点版本元数据。 */
    private record SpatialVersion(
            UUID waypointId,
            UUID coordinateSystemId,
            Instant validFrom,
            Instant validTo
    ) {
    }

    /** 同类稳定空间对象分组键。 */
    private record SpatialObjectKey(String objectType, UUID objectId) {
    }

    /** 已发布空间版本的半开有效时间区间。 */
    private record PublishedInterval(
            String objectType,
            UUID objectId,
            Instant validFrom,
            Instant validTo
    ) {
    }

    /**
     * 运行快照单条不可变内容。
     *
     * @param itemKey 稳定条目键
     * @param itemType 固定条目类型
     * @param contentJson 规范化非敏感 JSON
     * @param contentHash 内容摘要
     */
    public record RuntimeItem(
            String itemKey,
            String itemType,
            String contentJson,
            String contentHash
    ) {
    }

    private record CoordinateSystem(
            UUID id,
            String status,
            boolean unified,
            CoordinateTransformer.TargetBounds bounds
    ) {
    }

    private record CurrentRevision(
            long revision,
            UUID snapshotId,
            int itemCount,
            String contentHash
    ) {
    }

    private record Activation(boolean applied, RuntimeSnapshot runtime) {
    }
}
