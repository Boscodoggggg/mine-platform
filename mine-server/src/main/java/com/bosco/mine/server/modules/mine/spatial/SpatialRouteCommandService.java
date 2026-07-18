package com.bosco.mine.server.modules.mine.spatial;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.model.spatial.SpatialClassifier;
import com.bosco.mine.model.spatial.SpatialPoint;
import com.bosco.mine.model.spatial.SpatialZone;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionUpdateRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionUpdateRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialDetectionPolicy;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialObjectResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialRouteWaypointRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialVertexRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionUpdateRequest;
import com.bosco.mine.server.modules.mine.spatial.model.GeofenceVersionRecord;
import com.bosco.mine.server.modules.mine.spatial.model.RouteVersionRecord;
import com.bosco.mine.server.modules.mine.spatial.model.SpatialObjectRecord;
import com.bosco.mine.server.modules.mine.spatial.model.WaypointVersionRecord;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理途径点、路线和电子围栏的草稿、发布及不可变历史。
 */
@Service
public class SpatialRouteCommandService {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Set<String> WAYPOINT_TYPES =
            Set.of("LOAD", "UNLOAD", "CHECK", "WAIT", "ENTRY", "EXIT");
    private static final Set<String> ROUTE_DIRECTIONS =
            Set.of("FORWARD", "REVERSE", "BIDIRECTIONAL");
    private static final Set<String> GEOFENCE_TYPES =
            Set.of("RESTRICTED", "WORK", "LOAD", "UNLOAD", "WAIT", "SAFETY", "OTHER");
    private static final Set<String> VEHICLE_TYPES = Set.of(
            "HAUL_TRUCK", "EXCAVATOR", "LOADER", "DRILL", "DOZER",
            "WATER_TRUCK", "LIGHT_VEHICLE", "OTHER");

    private static final ErrorCode OBJECT_CONFLICT =
            new ErrorCode("MINE-SPATIAL-409-001", "空间对象编码已存在或稳定对象已被修改");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("MINE-SPATIAL-409-002", "空间草稿已发布或被其他管理员修改");
    private static final ErrorCode REQUEST_INVALID =
            new ErrorCode("MINE-SPATIAL-422-002", "空间对象坐标、几何或版本参数不合法");
    private static final ErrorCode REFERENCE_INVALID =
            new ErrorCode("MINE-SPATIAL-422-003", "路线引用必须是同矿区同坐标且覆盖有效期的已发布途径点版本");
    private static final ErrorCode WAYPOINT_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-404-001", "途径点不存在或已归档");
    private static final ErrorCode ROUTE_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-404-002", "路线不存在或已归档");
    private static final ErrorCode GEOFENCE_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-404-003", "电子围栏不存在或已归档");
    private static final ErrorCode VERSION_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-404-004", "空间对象版本不存在");

    private final SpatialRouteMapper mapper;
    private final SpatialRouteQueryService queryService;
    private final MineScopeAuthorizer authorizer;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final SpatialClassifier classifier = new SpatialClassifier();
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建空间主数据命令服务。
     *
     * @param mapper 空间主数据访问接口
     * @param queryService 空间主数据查询服务
     * @param authorizer 矿区数据作用域授权器
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public SpatialRouteCommandService(
            SpatialRouteMapper mapper,
            SpatialRouteQueryService queryService,
            MineScopeAuthorizer authorizer,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.queryService = queryService;
        this.authorizer = authorizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 创建稳定途径点。 */
    @Transactional
    public SpatialObjectResponse createWaypoint(UUID siteId, String code) {
        MineSite site = authorizer.requireSite(siteId);
        UUID id = idGenerator.next();
        try {
            mapper.insertWaypoint(
                    id, site.organizationId(), site.id(), normalizeCode(code),
                    authorizer.currentUserId());
        } catch (DuplicateKeyException exception) {
            throw conflict(id);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        SpatialObjectResponse created = queryService.findWaypoint(site.id(), id);
        audit("CREATE_WAYPOINT", "mine:spatial:edit", "MINE_WAYPOINT", id,
                Map.of(), objectSummary(created));
        return created;
    }

    /** 使用乐观锁归档稳定途径点。 */
    @Transactional
    public void archiveWaypoint(UUID siteId, UUID waypointId, long version) {
        MineSite site = authorizer.requireSite(siteId);
        SpatialObjectRecord before = requireActiveWaypoint(site, waypointId);
        if (mapper.archiveWaypoint(
                site.organizationId(), site.id(), waypointId, version,
                authorizer.currentUserId()) == 0) {
            throw conflict(waypointId);
        }
        SpatialObjectResponse after = queryService.findWaypoint(site.id(), waypointId);
        audit("ARCHIVE_WAYPOINT", "mine:spatial:edit", "MINE_WAYPOINT", waypointId,
                objectSummary(SpatialRouteQueryService.toResponse(before)), objectSummary(after));
    }

    /** 创建途径点草稿版本。 */
    @Transactional
    public WaypointVersionResponse createWaypointVersion(
            UUID siteId, UUID waypointId, WaypointVersionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveWaypoint(site, waypointId);
        validateUnifiedCoordinate(site, request.coordinateSystemId());
        WaypointVersionRequest normalized = normalize(request);
        UUID versionId = idGenerator.next();
        try {
            mapper.insertWaypointVersion(toWrite(
                    site, waypointId, versionId, normalized, authorizer.currentUserId()));
        } catch (DuplicateKeyException exception) {
            throw versionConflict(versionId, exception);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        WaypointVersionResponse created = queryService.findWaypointVersion(
                site.id(), waypointId, versionId);
        audit("CREATE_WAYPOINT_VERSION", "mine:spatial:edit", "MINE_WAYPOINT_VERSION", versionId,
                Map.of(), waypointSummary(created));
        return created;
    }

    /** 使用乐观锁修改途径点草稿版本。 */
    @Transactional
    public WaypointVersionResponse updateWaypointVersion(
            UUID siteId,
            UUID waypointId,
            UUID versionId,
            WaypointVersionUpdateRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveWaypoint(site, waypointId);
        WaypointVersionRecord before = requireWaypointVersion(site, waypointId, versionId);
        validateUnifiedCoordinate(site, request.coordinateSystemId());
        WaypointVersionUpdateRequest normalized = normalize(request);
        if (!"DRAFT".equals(before.status()) || before.version() != request.version()) {
            throw versionConflict(versionId);
        }
        try {
            if (mapper.updateWaypointVersion(toWrite(
                    site, waypointId, versionId, before.versionNumber(), normalized,
                    authorizer.currentUserId())) == 0) {
                throw versionConflict(versionId);
            }
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        WaypointVersionResponse updated = queryService.findWaypointVersion(
                site.id(), waypointId, versionId);
        audit("UPDATE_WAYPOINT_VERSION", "mine:spatial:edit", "MINE_WAYPOINT_VERSION", versionId,
                waypointSummary(SpatialRouteQueryService.toResponse(before)), waypointSummary(updated));
        return updated;
    }

    /** 发布并永久锁定途径点版本。 */
    @Transactional
    public WaypointVersionResponse publishWaypointVersion(
            UUID siteId, UUID waypointId, UUID versionId, long version
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveWaypoint(site, waypointId);
        WaypointVersionRecord before = requireWaypointVersion(site, waypointId, versionId);
        if (!"DRAFT".equals(before.status()) || before.version() != version) {
            throw versionConflict(versionId);
        }
        validateUnifiedCoordinate(site, before.coordinateSystemId());
        String contentHash = hash(waypointHashSource(before));
        try {
            if (mapper.publishWaypointVersion(
                    site.organizationId(), site.id(), waypointId, versionId,
                    contentHash, version, authorizer.currentUserId()) == 0) {
                throw versionConflict(versionId);
            }
        } catch (DataIntegrityViolationException exception) {
            throw versionConflict(versionId, exception);
        }
        WaypointVersionResponse published = queryService.findWaypointVersion(
                site.id(), waypointId, versionId);
        audit("PUBLISH_WAYPOINT_VERSION", "mine:spatial:publish", "MINE_WAYPOINT_VERSION", versionId,
                waypointSummary(SpatialRouteQueryService.toResponse(before)), waypointSummary(published));
        return published;
    }

    /** 创建稳定路线。 */
    @Transactional
    public SpatialObjectResponse createRoute(UUID siteId, String code) {
        MineSite site = authorizer.requireSite(siteId);
        UUID id = idGenerator.next();
        try {
            mapper.insertRoute(
                    id, site.organizationId(), site.id(), normalizeCode(code),
                    authorizer.currentUserId());
        } catch (DuplicateKeyException exception) {
            throw conflict(id);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        SpatialObjectResponse created = queryService.findRoute(site.id(), id);
        audit("CREATE_ROUTE", "mine:spatial:edit", "MINE_ROUTE", id,
                Map.of(), objectSummary(created));
        return created;
    }

    /** 使用乐观锁归档稳定路线。 */
    @Transactional
    public void archiveRoute(UUID siteId, UUID routeId, long version) {
        MineSite site = authorizer.requireSite(siteId);
        SpatialObjectRecord before = requireActiveRoute(site, routeId);
        if (mapper.archiveRoute(
                site.organizationId(), site.id(), routeId, version,
                authorizer.currentUserId()) == 0) {
            throw conflict(routeId);
        }
        SpatialObjectResponse after = queryService.findRoute(site.id(), routeId);
        audit("ARCHIVE_ROUTE", "mine:spatial:edit", "MINE_ROUTE", routeId,
                objectSummary(SpatialRouteQueryService.toResponse(before)), objectSummary(after));
    }

    /** 创建路线草稿版本及其有序途径点。 */
    @Transactional
    public RouteVersionResponse createRouteVersion(
            UUID siteId, UUID routeId, RouteVersionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveRoute(site, routeId);
        validateUnifiedCoordinate(site, request.coordinateSystemId());
        NormalizedRoute normalized = normalize(site, request);
        UUID versionId = idGenerator.next();
        try {
            mapper.insertRouteVersion(toWrite(
                    site, routeId, versionId, normalized, authorizer.currentUserId(), 0L));
            mapper.insertRouteWaypoints(routeWaypointWrites(site, versionId, normalized.waypoints()));
        } catch (DuplicateKeyException exception) {
            throw versionConflict(versionId, exception);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        RouteVersionResponse created = queryService.findRouteVersion(site.id(), routeId, versionId);
        audit("CREATE_ROUTE_VERSION", "mine:spatial:edit", "MINE_ROUTE_VERSION", versionId,
                Map.of(), routeSummary(created));
        return created;
    }

    /** 使用乐观锁修改路线草稿版本及其有序途径点。 */
    @Transactional
    public RouteVersionResponse updateRouteVersion(
            UUID siteId,
            UUID routeId,
            UUID versionId,
            RouteVersionUpdateRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveRoute(site, routeId);
        RouteVersionRecord before = requireRouteVersion(site, routeId, versionId);
        validateUnifiedCoordinate(site, request.coordinateSystemId());
        NormalizedRoute normalized = normalize(site, request, before.versionNumber());
        if (!"DRAFT".equals(before.status()) || before.version() != request.version()) {
            throw versionConflict(versionId);
        }
        RouteVersionResponse beforeResponse = queryService.findRouteVersion(
                site.id(), routeId, versionId);
        try {
            if (mapper.updateRouteVersion(toWrite(
                    site, routeId, versionId, normalized, authorizer.currentUserId(),
                    request.version())) == 0) {
                throw versionConflict(versionId);
            }
            mapper.deleteRouteWaypoints(versionId);
            mapper.insertRouteWaypoints(routeWaypointWrites(site, versionId, normalized.waypoints()));
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        RouteVersionResponse updated = queryService.findRouteVersion(site.id(), routeId, versionId);
        audit("UPDATE_ROUTE_VERSION", "mine:spatial:edit", "MINE_ROUTE_VERSION", versionId,
                routeSummary(beforeResponse), routeSummary(updated));
        return updated;
    }

    /** 发布并永久锁定路线版本及其途径点顺序。 */
    @Transactional
    public RouteVersionResponse publishRouteVersion(
            UUID siteId, UUID routeId, UUID versionId, long version
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveRoute(site, routeId);
        RouteVersionRecord before = requireRouteVersion(site, routeId, versionId);
        if (!"DRAFT".equals(before.status()) || before.version() != version) {
            throw versionConflict(versionId);
        }
        RouteVersionResponse draft = queryService.findRouteVersion(site.id(), routeId, versionId);
        validateRouteReferences(
                site, draft.coordinateSystemId(), draft.validFrom(), draft.validTo(),
                draft.waypoints().stream().map(item -> item.waypointVersionId()).toList());
        String contentHash = hash(routeHashSource(draft));
        try {
            if (mapper.publishRouteVersion(
                    site.organizationId(), site.id(), routeId, versionId,
                    contentHash, version, authorizer.currentUserId()) == 0) {
                throw versionConflict(versionId);
            }
        } catch (DataIntegrityViolationException exception) {
            throw versionConflict(versionId, exception);
        }
        RouteVersionResponse published = queryService.findRouteVersion(site.id(), routeId, versionId);
        audit("PUBLISH_ROUTE_VERSION", "mine:spatial:publish", "MINE_ROUTE_VERSION", versionId,
                routeSummary(draft), routeSummary(published));
        return published;
    }

    /** 创建稳定电子围栏。 */
    @Transactional
    public SpatialObjectResponse createGeofence(UUID siteId, String code) {
        MineSite site = authorizer.requireSite(siteId);
        UUID id = idGenerator.next();
        try {
            mapper.insertGeofence(
                    id, site.organizationId(), site.id(), normalizeCode(code),
                    authorizer.currentUserId());
        } catch (DuplicateKeyException exception) {
            throw conflict(id);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        SpatialObjectResponse created = queryService.findGeofence(site.id(), id);
        audit("CREATE_GEOFENCE", "mine:spatial:edit", "MINE_GEOFENCE", id,
                Map.of(), objectSummary(created));
        return created;
    }

    /** 使用乐观锁归档稳定电子围栏。 */
    @Transactional
    public void archiveGeofence(UUID siteId, UUID geofenceId, long version) {
        MineSite site = authorizer.requireSite(siteId);
        SpatialObjectRecord before = requireActiveGeofence(site, geofenceId);
        if (mapper.archiveGeofence(
                site.organizationId(), site.id(), geofenceId, version,
                authorizer.currentUserId()) == 0) {
            throw conflict(geofenceId);
        }
        SpatialObjectResponse after = queryService.findGeofence(site.id(), geofenceId);
        audit("ARCHIVE_GEOFENCE", "mine:spatial:edit", "MINE_GEOFENCE", geofenceId,
                objectSummary(SpatialRouteQueryService.toResponse(before)), objectSummary(after));
    }

    /** 创建电子围栏草稿版本及其有序顶点。 */
    @Transactional
    public GeofenceVersionResponse createGeofenceVersion(
            UUID siteId, UUID geofenceId, GeofenceVersionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveGeofence(site, geofenceId);
        validateUnifiedCoordinate(site, request.coordinateSystemId());
        NormalizedGeofence normalized = normalize(request);
        UUID versionId = idGenerator.next();
        try {
            mapper.insertGeofenceVersion(toWrite(
                    site, geofenceId, versionId, normalized, authorizer.currentUserId(), 0L));
            mapper.insertGeofenceVertices(geofenceVertexWrites(site, versionId, normalized.vertices()));
        } catch (DuplicateKeyException exception) {
            throw versionConflict(versionId, exception);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        GeofenceVersionResponse created = queryService.findGeofenceVersion(
                site.id(), geofenceId, versionId);
        audit("CREATE_GEOFENCE_VERSION", "mine:spatial:edit", "MINE_GEOFENCE_VERSION", versionId,
                Map.of(), geofenceSummary(created));
        return created;
    }

    /** 使用乐观锁修改电子围栏草稿版本及其有序顶点。 */
    @Transactional
    public GeofenceVersionResponse updateGeofenceVersion(
            UUID siteId,
            UUID geofenceId,
            UUID versionId,
            GeofenceVersionUpdateRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveGeofence(site, geofenceId);
        GeofenceVersionRecord before = requireGeofenceVersion(site, geofenceId, versionId);
        validateUnifiedCoordinate(site, request.coordinateSystemId());
        NormalizedGeofence normalized = normalize(request, before.versionNumber());
        if (!"DRAFT".equals(before.status()) || before.version() != request.version()) {
            throw versionConflict(versionId);
        }
        GeofenceVersionResponse beforeResponse = queryService.findGeofenceVersion(
                site.id(), geofenceId, versionId);
        try {
            if (mapper.updateGeofenceVersion(toWrite(
                    site, geofenceId, versionId, normalized, authorizer.currentUserId(),
                    request.version())) == 0) {
                throw versionConflict(versionId);
            }
            mapper.deleteGeofenceVertices(versionId);
            mapper.insertGeofenceVertices(geofenceVertexWrites(site, versionId, normalized.vertices()));
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        GeofenceVersionResponse updated = queryService.findGeofenceVersion(
                site.id(), geofenceId, versionId);
        audit("UPDATE_GEOFENCE_VERSION", "mine:spatial:edit", "MINE_GEOFENCE_VERSION", versionId,
                geofenceSummary(beforeResponse), geofenceSummary(updated));
        return updated;
    }

    /** 发布并永久锁定电子围栏版本及其顶点。 */
    @Transactional
    public GeofenceVersionResponse publishGeofenceVersion(
            UUID siteId, UUID geofenceId, UUID versionId, long version
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveGeofence(site, geofenceId);
        GeofenceVersionRecord before = requireGeofenceVersion(site, geofenceId, versionId);
        if (!"DRAFT".equals(before.status()) || before.version() != version) {
            throw versionConflict(versionId);
        }
        GeofenceVersionResponse draft = queryService.findGeofenceVersion(
                site.id(), geofenceId, versionId);
        validatePolygon(
                draft.vertices().stream()
                        .map(vertex -> new SpatialVertexRequest(
                                vertex.sequenceNumber(), vertex.x(), vertex.y()))
                        .toList(),
                draft.detectionPolicy().boundaryToleranceMeters(), draft.minZ(), draft.maxZ());
        String contentHash = hash(geofenceHashSource(draft));
        try {
            if (mapper.publishGeofenceVersion(
                    site.organizationId(), site.id(), geofenceId, versionId,
                    contentHash, version, authorizer.currentUserId()) == 0) {
                throw versionConflict(versionId);
            }
        } catch (DataIntegrityViolationException exception) {
            throw versionConflict(versionId, exception);
        }
        GeofenceVersionResponse published = queryService.findGeofenceVersion(
                site.id(), geofenceId, versionId);
        audit("PUBLISH_GEOFENCE_VERSION", "mine:spatial:publish", "MINE_GEOFENCE_VERSION", versionId,
                geofenceSummary(draft), geofenceSummary(published));
        return published;
    }

    private SpatialObjectRecord requireActiveWaypoint(MineSite site, UUID id) {
        return mapper.findWaypointById(site.organizationId(), site.id(), id)
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> notFound(WAYPOINT_NOT_FOUND, id));
    }

    private SpatialObjectRecord requireActiveRoute(MineSite site, UUID id) {
        return mapper.findRouteById(site.organizationId(), site.id(), id)
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> notFound(ROUTE_NOT_FOUND, id));
    }

    private SpatialObjectRecord requireActiveGeofence(MineSite site, UUID id) {
        return mapper.findGeofenceById(site.organizationId(), site.id(), id)
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> notFound(GEOFENCE_NOT_FOUND, id));
    }

    private WaypointVersionRecord requireWaypointVersion(
            MineSite site, UUID parentId, UUID versionId
    ) {
        return mapper.findWaypointVersionById(
                        site.organizationId(), site.id(), parentId, versionId)
                .orElseThrow(() -> notFound(VERSION_NOT_FOUND, versionId));
    }

    private RouteVersionRecord requireRouteVersion(
            MineSite site, UUID parentId, UUID versionId
    ) {
        return mapper.findRouteVersionById(
                        site.organizationId(), site.id(), parentId, versionId)
                .orElseThrow(() -> notFound(VERSION_NOT_FOUND, versionId));
    }

    private GeofenceVersionRecord requireGeofenceVersion(
            MineSite site, UUID parentId, UUID versionId
    ) {
        return mapper.findGeofenceVersionById(
                        site.organizationId(), site.id(), parentId, versionId)
                .orElseThrow(() -> notFound(VERSION_NOT_FOUND, versionId));
    }

    private void validateUnifiedCoordinate(MineSite site, UUID coordinateSystemId) {
        if (coordinateSystemId == null || !mapper.isActiveUnifiedCoordinateSystem(
                site.organizationId(), site.id(), coordinateSystemId)) {
            throw new MineException(REQUEST_INVALID, Map.of("siteId", site.id()));
        }
    }

    private WaypointVersionRequest normalize(WaypointVersionRequest request) {
        if (request == null || request.versionNumber() < 1) throw invalid();
        String name = name(request.name());
        String type = allowedUpper(request.waypointType(), WAYPOINT_TYPES);
        SpatialDetectionPolicy policy = policy(request.detectionPolicy());
        validity(request.validFrom(), request.validTo());
        try {
            SpatialZone.Circle zone = new SpatialZone.Circle(
                    request.x(), request.y(), request.radiusMeters(),
                    policy.boundaryToleranceMeters(), request.minZ(), request.maxZ());
            if (request.radiusMeters() > 10_000D) throw invalid();
            if (request.z() != null && !Double.isFinite(request.z())) throw invalid();
            return new WaypointVersionRequest(
                    request.coordinateSystemId(), request.versionNumber(), name, type,
                    zone.centerX(), zone.centerY(), normalizeZero(request.z()), zone.radiusMeters(),
                    zone.minZ(), zone.maxZ(), policy, request.validFrom(), request.validTo());
        } catch (IllegalArgumentException exception) {
            throw invalid(exception);
        }
    }

    private WaypointVersionUpdateRequest normalize(WaypointVersionUpdateRequest request) {
        if (request == null || request.version() < 0) throw invalid();
        WaypointVersionRequest normalized = normalize(new WaypointVersionRequest(
                request.coordinateSystemId(), 1, request.name(), request.waypointType(),
                request.x(), request.y(), request.z(), request.radiusMeters(),
                request.minZ(), request.maxZ(), request.detectionPolicy(),
                request.validFrom(), request.validTo()));
        return new WaypointVersionUpdateRequest(
                normalized.coordinateSystemId(), normalized.name(), normalized.waypointType(),
                normalized.x(), normalized.y(), normalized.z(), normalized.radiusMeters(),
                normalized.minZ(), normalized.maxZ(), normalized.detectionPolicy(),
                normalized.validFrom(), normalized.validTo(), request.version());
    }

    private NormalizedRoute normalize(MineSite site, RouteVersionRequest request) {
        if (request == null || request.versionNumber() < 1) throw invalid();
        return normalizeRoute(
                site, request.coordinateSystemId(), request.versionNumber(), request.name(),
                request.direction(), request.applicableVehicleTypes(), request.validFrom(),
                request.validTo(), request.waypoints());
    }

    private NormalizedRoute normalize(
            MineSite site, RouteVersionUpdateRequest request, int versionNumber
    ) {
        if (request == null || request.version() < 0) throw invalid();
        return normalizeRoute(
                site, request.coordinateSystemId(), versionNumber, request.name(),
                request.direction(), request.applicableVehicleTypes(), request.validFrom(),
                request.validTo(), request.waypoints());
    }

    private NormalizedRoute normalizeRoute(
            MineSite site,
            UUID coordinateSystemId,
            int versionNumber,
            String name,
            String direction,
            List<String> vehicleTypes,
            Instant validFrom,
            Instant validTo,
            List<SpatialRouteWaypointRequest> waypoints
    ) {
        validity(validFrom, validTo);
        if (vehicleTypes == null || vehicleTypes.isEmpty() || vehicleTypes.size() > 16) {
            throw invalid();
        }
        List<String> normalizedVehicleTypes = vehicleTypes.stream()
                .map(value -> allowedUpper(value, VEHICLE_TYPES)).distinct().sorted().toList();
        if (normalizedVehicleTypes.size() != vehicleTypes.size()) throw invalid();
        List<SpatialRouteWaypointRequest> normalizedWaypoints = sequenceWaypoints(waypoints);
        validateRouteReferences(
                site, coordinateSystemId, validFrom, validTo,
                normalizedWaypoints.stream().map(SpatialRouteWaypointRequest::waypointVersionId).toList());
        return new NormalizedRoute(
                coordinateSystemId, versionNumber, name(name),
                allowedUpper(direction, ROUTE_DIRECTIONS), normalizedVehicleTypes,
                validFrom, validTo, normalizedWaypoints);
    }

    private void validateRouteReferences(
            MineSite site,
            UUID coordinateSystemId,
            Instant validFrom,
            Instant validTo,
            List<UUID> versionIds
    ) {
        if (versionIds == null || versionIds.size() < 2 || versionIds.size() > 10_000
                || versionIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new MineException(REFERENCE_INVALID, Map.of());
        }
        List<UUID> distinct = versionIds.stream().distinct().toList();
        long compatible = mapper.countCompatibleWaypointVersions(
                site.organizationId(), site.id(), coordinateSystemId,
                validFrom, validTo, distinct);
        if (compatible != distinct.size()) {
            throw new MineException(REFERENCE_INVALID, Map.of("siteId", site.id()));
        }
    }

    private NormalizedGeofence normalize(GeofenceVersionRequest request) {
        if (request == null || request.versionNumber() < 1) throw invalid();
        return normalizeGeofence(
                request.coordinateSystemId(), request.versionNumber(), request.name(),
                request.geofenceType(), request.minZ(), request.maxZ(),
                request.detectionPolicy(), request.validFrom(), request.validTo(), request.vertices());
    }

    private NormalizedGeofence normalize(
            GeofenceVersionUpdateRequest request, int versionNumber
    ) {
        if (request == null || request.version() < 0) throw invalid();
        return normalizeGeofence(
                request.coordinateSystemId(), versionNumber, request.name(),
                request.geofenceType(), request.minZ(), request.maxZ(),
                request.detectionPolicy(), request.validFrom(), request.validTo(), request.vertices());
    }

    private NormalizedGeofence normalizeGeofence(
            UUID coordinateSystemId,
            int versionNumber,
            String name,
            String type,
            Double minZ,
            Double maxZ,
            SpatialDetectionPolicy detectionPolicy,
            Instant validFrom,
            Instant validTo,
            List<SpatialVertexRequest> vertices
    ) {
        SpatialDetectionPolicy normalizedPolicy = policy(detectionPolicy);
        validity(validFrom, validTo);
        List<SpatialVertexRequest> normalizedVertices = sequenceVertices(vertices);
        validatePolygon(
                normalizedVertices, normalizedPolicy.boundaryToleranceMeters(), minZ, maxZ);
        return new NormalizedGeofence(
                coordinateSystemId, versionNumber, name(name),
                allowedUpper(type, GEOFENCE_TYPES), minZ, maxZ,
                normalizedPolicy, validFrom, validTo, normalizedVertices);
    }

    private List<SpatialRouteWaypointRequest> sequenceWaypoints(
            List<SpatialRouteWaypointRequest> values
    ) {
        if (values == null || values.size() < 2 || values.size() > 10_000) throw invalid();
        if (values.stream().anyMatch(Objects::isNull)) throw invalid();
        List<SpatialRouteWaypointRequest> sorted = values.stream()
                .sorted(Comparator.comparingInt(SpatialRouteWaypointRequest::sequenceNumber))
                .toList();
        for (int index = 0; index < sorted.size(); index++) {
            SpatialRouteWaypointRequest item = sorted.get(index);
            if (item == null || item.sequenceNumber() != index + 1
                    || item.waypointVersionId() == null
                    || item.expectedDwellSeconds() != null
                    && (item.expectedDwellSeconds() < 0 || item.expectedDwellSeconds() > 86_400)) {
                throw invalid();
            }
        }
        return sorted;
    }

    private List<SpatialVertexRequest> sequenceVertices(List<SpatialVertexRequest> values) {
        if (values == null || values.size() < 3 || values.size() > 10_000) throw invalid();
        if (values.stream().anyMatch(Objects::isNull)) throw invalid();
        List<SpatialVertexRequest> sorted = values.stream()
                .sorted(Comparator.comparingInt(SpatialVertexRequest::sequenceNumber))
                .toList();
        for (int index = 0; index < sorted.size(); index++) {
            SpatialVertexRequest item = sorted.get(index);
            if (item == null || item.sequenceNumber() != index + 1
                    || !Double.isFinite(item.x()) || !Double.isFinite(item.y())) {
                throw invalid();
            }
        }
        return sorted;
    }

    private void validatePolygon(
            List<SpatialVertexRequest> vertices,
            double boundaryToleranceMeters,
            Double minZ,
            Double maxZ
    ) {
        try {
            SpatialZone.Polygon polygon = new SpatialZone.Polygon(
                    vertices.stream()
                            .map(vertex -> new SpatialZone.Vertex(vertex.x(), vertex.y()))
                            .toList(),
                    boundaryToleranceMeters, minZ, maxZ);
            SpatialZone.Vertex first = polygon.vertices().getFirst();
            classifier.classify(polygon, new SpatialPoint(first.x(), first.y(), minZ));
        } catch (IllegalArgumentException exception) {
            throw invalid(exception);
        }
    }

    private SpatialDetectionPolicy policy(SpatialDetectionPolicy value) {
        if (value == null
                || !Double.isFinite(value.boundaryToleranceMeters())
                || value.boundaryToleranceMeters() < 0D
                || value.boundaryToleranceMeters() > 1_000D
                || value.enterConfirmPoints() < 1 || value.enterConfirmPoints() > 20
                || value.exitConfirmPoints() < 1 || value.exitConfirmPoints() > 20
                || value.dwellSeconds() < 0 || value.dwellSeconds() > 86_400
                || value.allowedLatenessSeconds() < 0 || value.allowedLatenessSeconds() > 300
                || value.idleFlushSeconds() < 1 || value.idleFlushSeconds() > 3_600
                || value.idleFlushSeconds() <= value.allowedLatenessSeconds()) {
            throw invalid();
        }
        return value;
    }

    private void validity(Instant validFrom, Instant validTo) {
        if (validFrom == null || validTo != null && !validTo.isAfter(validFrom)) throw invalid();
    }

    private SpatialRouteMapper.WaypointVersionWrite toWrite(
            MineSite site,
            UUID parentId,
            UUID id,
            WaypointVersionRequest request,
            UUID operatorId
    ) {
        return new SpatialRouteMapper.WaypointVersionWrite(
                id, parentId, site.organizationId(), site.id(), request.coordinateSystemId(),
                request.versionNumber(), request.name(), request.waypointType(),
                request.x(), request.y(), request.z(), request.radiusMeters(),
                request.minZ(), request.maxZ(), request.detectionPolicy().boundaryToleranceMeters(),
                request.detectionPolicy().enterConfirmPoints(),
                request.detectionPolicy().exitConfirmPoints(), request.detectionPolicy().dwellSeconds(),
                request.detectionPolicy().allowedLatenessSeconds(),
                request.detectionPolicy().idleFlushSeconds(), request.validFrom(), request.validTo(),
                operatorId, 0L);
    }

    private SpatialRouteMapper.WaypointVersionWrite toWrite(
            MineSite site,
            UUID parentId,
            UUID id,
            int versionNumber,
            WaypointVersionUpdateRequest request,
            UUID operatorId
    ) {
        return new SpatialRouteMapper.WaypointVersionWrite(
                id, parentId, site.organizationId(), site.id(), request.coordinateSystemId(),
                versionNumber, request.name(), request.waypointType(),
                request.x(), request.y(), request.z(), request.radiusMeters(),
                request.minZ(), request.maxZ(), request.detectionPolicy().boundaryToleranceMeters(),
                request.detectionPolicy().enterConfirmPoints(),
                request.detectionPolicy().exitConfirmPoints(), request.detectionPolicy().dwellSeconds(),
                request.detectionPolicy().allowedLatenessSeconds(),
                request.detectionPolicy().idleFlushSeconds(), request.validFrom(), request.validTo(),
                operatorId, request.version());
    }

    private SpatialRouteMapper.RouteVersionWrite toWrite(
            MineSite site,
            UUID parentId,
            UUID id,
            NormalizedRoute item,
            UUID operatorId,
            long lockVersion
    ) {
        return new SpatialRouteMapper.RouteVersionWrite(
                id, parentId, site.organizationId(), site.id(), item.coordinateSystemId(),
                item.versionNumber(), item.name(), item.direction(),
                writeJson(item.vehicleTypes()), item.validFrom(), item.validTo(),
                operatorId, lockVersion);
    }

    private List<SpatialRouteMapper.RouteWaypointWrite> routeWaypointWrites(
            MineSite site, UUID versionId, List<SpatialRouteWaypointRequest> items
    ) {
        return items.stream().map(item -> new SpatialRouteMapper.RouteWaypointWrite(
                versionId, item.waypointVersionId(), site.organizationId(), site.id(),
                item.sequenceNumber(), item.stopRequired(), item.expectedDwellSeconds())).toList();
    }

    private SpatialRouteMapper.GeofenceVersionWrite toWrite(
            MineSite site,
            UUID parentId,
            UUID id,
            NormalizedGeofence item,
            UUID operatorId,
            long lockVersion
    ) {
        return new SpatialRouteMapper.GeofenceVersionWrite(
                id, parentId, site.organizationId(), site.id(), item.coordinateSystemId(),
                item.versionNumber(), item.name(), item.type(), item.minZ(), item.maxZ(),
                item.policy().boundaryToleranceMeters(), item.policy().enterConfirmPoints(),
                item.policy().exitConfirmPoints(), item.policy().dwellSeconds(),
                item.policy().allowedLatenessSeconds(), item.policy().idleFlushSeconds(),
                item.validFrom(), item.validTo(), operatorId, lockVersion);
    }

    private List<SpatialRouteMapper.GeofenceVertexWrite> geofenceVertexWrites(
            MineSite site, UUID versionId, List<SpatialVertexRequest> items
    ) {
        return items.stream().map(item -> new SpatialRouteMapper.GeofenceVertexWrite(
                versionId, site.organizationId(), site.id(),
                item.sequenceNumber(), item.x(), item.y())).toList();
    }

    private Map<String, Object> waypointHashSource(WaypointVersionRecord item) {
        Map<String, Object> value = new TreeMap<>();
        value.put("coordinateSystemId", item.coordinateSystemId().toString());
        value.put("versionNumber", item.versionNumber());
        value.put("name", item.name());
        value.put("waypointType", item.waypointType());
        value.put("x", item.x());
        value.put("y", item.y());
        value.put("z", item.z());
        value.put("radiusMeters", item.radiusMeters());
        value.put("minZ", item.minZ());
        value.put("maxZ", item.maxZ());
        value.put("boundaryToleranceMeters", item.boundaryToleranceMeters());
        value.put("enterConfirmPoints", item.enterConfirmPoints());
        value.put("exitConfirmPoints", item.exitConfirmPoints());
        value.put("dwellSeconds", item.dwellSeconds());
        value.put("allowedLatenessSeconds", item.allowedLatenessSeconds());
        value.put("idleFlushSeconds", item.idleFlushSeconds());
        value.put("validFrom", item.validFrom().toString());
        value.put("validTo", string(item.validTo()));
        return value;
    }

    private Map<String, Object> routeHashSource(RouteVersionResponse item) {
        Map<String, Object> value = new TreeMap<>();
        value.put("coordinateSystemId", item.coordinateSystemId().toString());
        value.put("versionNumber", item.versionNumber());
        value.put("name", item.name());
        value.put("direction", item.direction());
        value.put("applicableVehicleTypes", item.applicableVehicleTypes());
        value.put("validFrom", item.validFrom().toString());
        value.put("validTo", string(item.validTo()));
        value.put("waypoints", item.waypoints().stream().map(waypoint -> {
            Map<String, Object> waypointValue = new TreeMap<>();
            waypointValue.put("sequenceNumber", waypoint.sequenceNumber());
            waypointValue.put("waypointVersionId", waypoint.waypointVersionId().toString());
            waypointValue.put("stopRequired", waypoint.stopRequired());
            waypointValue.put("expectedDwellSeconds", waypoint.expectedDwellSeconds());
            return waypointValue;
        }).toList());
        return value;
    }

    private Map<String, Object> geofenceHashSource(GeofenceVersionResponse item) {
        Map<String, Object> value = new TreeMap<>();
        value.put("coordinateSystemId", item.coordinateSystemId().toString());
        value.put("versionNumber", item.versionNumber());
        value.put("name", item.name());
        value.put("geofenceType", item.geofenceType());
        value.put("minZ", item.minZ());
        value.put("maxZ", item.maxZ());
        value.put("boundaryToleranceMeters", item.detectionPolicy().boundaryToleranceMeters());
        value.put("enterConfirmPoints", item.detectionPolicy().enterConfirmPoints());
        value.put("exitConfirmPoints", item.detectionPolicy().exitConfirmPoints());
        value.put("dwellSeconds", item.detectionPolicy().dwellSeconds());
        value.put("allowedLatenessSeconds", item.detectionPolicy().allowedLatenessSeconds());
        value.put("idleFlushSeconds", item.detectionPolicy().idleFlushSeconds());
        value.put("validFrom", item.validFrom().toString());
        value.put("validTo", string(item.validTo()));
        value.put("vertices", item.vertices().stream().map(vertex -> Map.of(
                "sequenceNumber", vertex.sequenceNumber(),
                "x", vertex.x(), "y", vertex.y())).toList());
        return value;
    }

    private String hash(Map<String, Object> value) {
        try {
            byte[] canonical = objectMapper.writeValueAsString(canonicalize(value))
                    .getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException exception) {
            throw invalid(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw invalid(exception);
        }
    }

    private void audit(
            String action,
            String permission,
            String resourceType,
            UUID resourceId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        auditService.recordSuccess(
                "MINE_MANAGEMENT", action, permission, resourceType, resourceId, before, after);
    }

    private Map<String, Object> objectSummary(SpatialObjectResponse item) {
        return summary(
                "siteId", item.siteId(), "code", item.code(),
                "status", item.status(), "version", item.version());
    }

    private Map<String, Object> waypointSummary(WaypointVersionResponse item) {
        return summary(
                "siteId", item.siteId(), "waypointId", item.waypointId(),
                "versionNumber", item.versionNumber(), "status", item.status(),
                "contentHash", item.contentHash(), "version", item.version());
    }

    private Map<String, Object> routeSummary(RouteVersionResponse item) {
        return summary(
                "siteId", item.siteId(), "routeId", item.routeId(),
                "versionNumber", item.versionNumber(), "waypointCount", item.waypoints().size(),
                "status", item.status(), "contentHash", item.contentHash(), "version", item.version());
    }

    private Map<String, Object> geofenceSummary(GeofenceVersionResponse item) {
        return summary(
                "siteId", item.siteId(), "geofenceId", item.geofenceId(),
                "versionNumber", item.versionNumber(), "vertexCount", item.vertices().size(),
                "status", item.status(), "contentHash", item.contentHash(), "version", item.version());
    }

    private Map<String, Object> summary(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private String normalizeCode(String value) {
        String code = required(value).toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) throw invalid();
        return code;
    }

    private String name(String value) {
        String result = required(value);
        if (result.length() > 128) throw invalid();
        return result;
    }

    private String allowedUpper(String value, Set<String> allowed) {
        String result = required(value).toUpperCase(Locale.ROOT);
        if (!allowed.contains(result)) throw invalid();
        return result;
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw invalid();
        return value.strip();
    }

    private Double normalizeZero(Double value) {
        return value == null ? null : value == 0D ? 0D : value;
    }

    private String string(Instant value) {
        return value == null ? null : value.toString();
    }

    private MineException notFound(ErrorCode errorCode, UUID id) {
        return new MineException(errorCode, Map.of("id", id));
    }

    private MineException conflict(UUID id) {
        return new MineException(OBJECT_CONFLICT, Map.of("id", id));
    }

    private MineException versionConflict(UUID id) {
        return new MineException(VERSION_CONFLICT, Map.of("versionId", id));
    }

    private MineException versionConflict(UUID id, Exception cause) {
        return new MineException(VERSION_CONFLICT, Map.of(
                "versionId", id, "exceptionType", cause.getClass().getSimpleName()));
    }

    private MineException invalid() {
        return new MineException(REQUEST_INVALID, Map.of());
    }

    private MineException invalid(Exception cause) {
        return new MineException(REQUEST_INVALID, Map.of(
                "exceptionType", cause.getClass().getSimpleName()));
    }

    /** 规范化路线版本输入。 */
    private record NormalizedRoute(
            UUID coordinateSystemId,
            int versionNumber,
            String name,
            String direction,
            List<String> vehicleTypes,
            Instant validFrom,
            Instant validTo,
            List<SpatialRouteWaypointRequest> waypoints
    ) {
    }

    /** 规范化电子围栏版本输入。 */
    private record NormalizedGeofence(
            UUID coordinateSystemId,
            int versionNumber,
            String name,
            String type,
            Double minZ,
            Double maxZ,
            SpatialDetectionPolicy policy,
            Instant validFrom,
            Instant validTo,
            List<SpatialVertexRequest> vertices
    ) {
    }
}
