package com.bosco.mine.server.modules.mine.spatial;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.mine.spatial.dto.CurrentSpatialRegionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialDetectionPolicy;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialObjectResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialRouteWaypointResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialVertexResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.model.GeofenceVersionRecord;
import com.bosco.mine.server.modules.mine.spatial.model.RouteVersionRecord;
import com.bosco.mine.server.modules.mine.spatial.model.SpatialObjectRecord;
import com.bosco.mine.server.modules.mine.spatial.model.WaypointVersionRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 按服务端矿区作用域查询途径点、路线和电子围栏。
 */
@Service
public class SpatialRouteQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("MINE-SPATIAL-422-001", "空间对象分页参数不合法");
    private static final ErrorCode WAYPOINT_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-404-001", "途径点不存在");
    private static final ErrorCode ROUTE_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-404-002", "路线不存在");
    private static final ErrorCode GEOFENCE_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-404-003", "电子围栏不存在");
    private static final ErrorCode VERSION_NOT_FOUND =
            new ErrorCode("MINE-SPATIAL-404-004", "空间对象版本不存在");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<SpatialVertexResponse>> SPATIAL_VERTICES =
            new TypeReference<>() { };

    private final SpatialRouteMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;

    /**
     * 创建空间主数据查询服务。
     *
     * @param mapper 空间主数据访问接口
     * @param authorizer 矿区数据作用域授权器
     * @param objectMapper JSON 映射器
     */
    public SpatialRouteQueryService(
            SpatialRouteMapper mapper,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.objectMapper = objectMapper;
    }

    /** 分页查询途径点稳定对象。 */
    @Transactional(readOnly = true)
    public PageResponse<SpatialObjectResponse> findWaypoints(
            UUID siteId, String keyword, String status, int page, int size
    ) {
        MineSite site = requirePageAndSite(siteId, page, size);
        String normalizedKeyword = optional(keyword);
        String normalizedStatus = optionalUpper(status);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                mapper.findWaypoints(
                                site.organizationId(), site.id(), normalizedKeyword,
                                normalizedStatus, offset, size)
                        .stream().map(SpatialRouteQueryService::toResponse).toList(),
                page, size,
                mapper.countWaypoints(
                        site.organizationId(), site.id(), normalizedKeyword, normalizedStatus));
    }

    /** 查询途径点稳定对象详情。 */
    @Transactional(readOnly = true)
    public SpatialObjectResponse findWaypoint(UUID siteId, UUID waypointId) {
        MineSite site = authorizer.requireSite(siteId);
        return mapper.findWaypointById(site.organizationId(), site.id(), waypointId)
                .map(SpatialRouteQueryService::toResponse)
                .orElseThrow(() -> notFound(WAYPOINT_NOT_FOUND, waypointId));
    }

    /** 查询途径点的全部历史版本。 */
    @Transactional(readOnly = true)
    public List<WaypointVersionResponse> findWaypointVersions(UUID siteId, UUID waypointId) {
        MineSite site = authorizer.requireSite(siteId);
        requireWaypoint(site, waypointId);
        return mapper.findWaypointVersions(site.organizationId(), site.id(), waypointId)
                .stream().map(SpatialRouteQueryService::toResponse).toList();
    }

    /** 查询指定途径点版本。 */
    @Transactional(readOnly = true)
    public WaypointVersionResponse findWaypointVersion(
            UUID siteId, UUID waypointId, UUID versionId
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireWaypoint(site, waypointId);
        return mapper.findWaypointVersionById(
                        site.organizationId(), site.id(), waypointId, versionId)
                .map(SpatialRouteQueryService::toResponse)
                .orElseThrow(() -> versionNotFound(versionId));
    }

    /** 分页查询路线稳定对象。 */
    @Transactional(readOnly = true)
    public PageResponse<SpatialObjectResponse> findRoutes(
            UUID siteId, String keyword, String status, int page, int size
    ) {
        MineSite site = requirePageAndSite(siteId, page, size);
        String normalizedKeyword = optional(keyword);
        String normalizedStatus = optionalUpper(status);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                mapper.findRoutes(
                                site.organizationId(), site.id(), normalizedKeyword,
                                normalizedStatus, offset, size)
                        .stream().map(SpatialRouteQueryService::toResponse).toList(),
                page, size,
                mapper.countRoutes(
                        site.organizationId(), site.id(), normalizedKeyword, normalizedStatus));
    }

    /** 查询路线稳定对象详情。 */
    @Transactional(readOnly = true)
    public SpatialObjectResponse findRoute(UUID siteId, UUID routeId) {
        MineSite site = authorizer.requireSite(siteId);
        return mapper.findRouteById(site.organizationId(), site.id(), routeId)
                .map(SpatialRouteQueryService::toResponse)
                .orElseThrow(() -> notFound(ROUTE_NOT_FOUND, routeId));
    }

    /** 查询路线的全部历史版本。 */
    @Transactional(readOnly = true)
    public List<RouteVersionResponse> findRouteVersions(UUID siteId, UUID routeId) {
        MineSite site = authorizer.requireSite(siteId);
        requireRoute(site, routeId);
        return mapper.findRouteVersions(site.organizationId(), site.id(), routeId)
                .stream().map(item -> toResponse(site, item)).toList();
    }

    /** 查询指定路线版本及有序途径点。 */
    @Transactional(readOnly = true)
    public RouteVersionResponse findRouteVersion(UUID siteId, UUID routeId, UUID versionId) {
        MineSite site = authorizer.requireSite(siteId);
        requireRoute(site, routeId);
        RouteVersionRecord version = mapper.findRouteVersionById(
                        site.organizationId(), site.id(), routeId, versionId)
                .orElseThrow(() -> versionNotFound(versionId));
        return toResponse(site, version);
    }

    /** 分页查询电子围栏稳定对象。 */
    @Transactional(readOnly = true)
    public PageResponse<SpatialObjectResponse> findGeofences(
            UUID siteId, String keyword, String status, int page, int size
    ) {
        MineSite site = requirePageAndSite(siteId, page, size);
        String normalizedKeyword = optional(keyword);
        String normalizedStatus = optionalUpper(status);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                mapper.findGeofences(
                                site.organizationId(), site.id(), normalizedKeyword,
                                normalizedStatus, offset, size)
                        .stream().map(SpatialRouteQueryService::toResponse).toList(),
                page, size,
                mapper.countGeofences(
                        site.organizationId(), site.id(), normalizedKeyword, normalizedStatus));
    }

    /** 查询电子围栏稳定对象详情。 */
    @Transactional(readOnly = true)
    public SpatialObjectResponse findGeofence(UUID siteId, UUID geofenceId) {
        MineSite site = authorizer.requireSite(siteId);
        return mapper.findGeofenceById(site.organizationId(), site.id(), geofenceId)
                .map(SpatialRouteQueryService::toResponse)
                .orElseThrow(() -> notFound(GEOFENCE_NOT_FOUND, geofenceId));
    }

    /** 查询电子围栏的全部历史版本。 */
    @Transactional(readOnly = true)
    public List<GeofenceVersionResponse> findGeofenceVersions(UUID siteId, UUID geofenceId) {
        MineSite site = authorizer.requireSite(siteId);
        requireGeofence(site, geofenceId);
        return mapper.findGeofenceVersions(site.organizationId(), site.id(), geofenceId)
                .stream().map(item -> toResponse(site, item)).toList();
    }

    /** 查询指定电子围栏版本及有序顶点。 */
    @Transactional(readOnly = true)
    public GeofenceVersionResponse findGeofenceVersion(
            UUID siteId, UUID geofenceId, UUID versionId
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireGeofence(site, geofenceId);
        GeofenceVersionRecord version = mapper.findGeofenceVersionById(
                        site.organizationId(), site.id(), geofenceId, versionId)
                .orElseThrow(() -> versionNotFound(versionId));
        return toResponse(site, version);
    }

    /** 查询当前生效区域，并返回前端安排自动刷新的最近有效期边界。 */
    @Transactional(readOnly = true)
    public CurrentSpatialRegionResponse findCurrentSpatialRegions(UUID siteId) {
        MineSite site = authorizer.requireSite(siteId);
        Instant evaluatedAt = Instant.now();
        List<CurrentSpatialRegionResponse.Item> items = mapper.findCurrentSpatialRegions(
                        site.organizationId(), site.id(), evaluatedAt)
                .stream()
                .map(item -> new CurrentSpatialRegionResponse.Item(
                        item.zoneType(), item.zoneId(), item.zoneVersionId(),
                        item.coordinateSystemId(), item.name(), item.contentHash(),
                        item.validFrom(), item.validTo(), item.shape(), item.x(), item.y(),
                        item.radiusMeters(), spatialVertices(item.verticesJson())))
                .toList();
        Instant nextChangeAt = mapper.findNextSpatialRegionChangeAt(
                site.organizationId(), site.id(), evaluatedAt);
        return new CurrentSpatialRegionResponse(site.id(), evaluatedAt, nextChangeAt, items);
    }

    /** 将稳定对象持久化快照转换为响应。 */
    public static SpatialObjectResponse toResponse(SpatialObjectRecord item) {
        return new SpatialObjectResponse(
                item.id(), item.organizationId(), item.siteId(), item.code(), item.status(),
                item.archivedAt(), item.createdAt(), item.updatedAt(), item.version());
    }

    /** 将途径点版本持久化快照转换为响应。 */
    public static WaypointVersionResponse toResponse(WaypointVersionRecord item) {
        return new WaypointVersionResponse(
                item.id(), item.waypointId(), item.organizationId(), item.siteId(),
                item.coordinateSystemId(), item.versionNumber(), item.name(), item.waypointType(),
                item.x(), item.y(), item.z(), item.radiusMeters(), item.minZ(), item.maxZ(),
                policy(item), item.validFrom(), item.validTo(), item.status(), item.contentHash(),
                item.publishedAt(), item.createdAt(), item.updatedAt(), item.version());
    }

    private RouteVersionResponse toResponse(MineSite site, RouteVersionRecord item) {
        return new RouteVersionResponse(
                item.id(), item.routeId(), item.organizationId(), item.siteId(),
                item.coordinateSystemId(), item.versionNumber(), item.name(), item.direction(),
                vehicleTypes(item.applicableVehicleTypesJson()), item.validFrom(), item.validTo(),
                item.status(), item.contentHash(), item.publishedAt(),
                mapper.findRouteWaypoints(site.organizationId(), site.id(), item.id()).stream()
                        .map(waypoint -> new SpatialRouteWaypointResponse(
                                waypoint.sequenceNumber(), waypoint.waypointVersionId(),
                                waypoint.stopRequired(), waypoint.expectedDwellSeconds()))
                        .toList(),
                item.createdAt(), item.updatedAt(), item.version());
    }

    private GeofenceVersionResponse toResponse(MineSite site, GeofenceVersionRecord item) {
        return new GeofenceVersionResponse(
                item.id(), item.geofenceId(), item.organizationId(), item.siteId(),
                item.coordinateSystemId(), item.versionNumber(), item.name(), item.geofenceType(),
                item.minZ(), item.maxZ(), policy(item), item.validFrom(), item.validTo(),
                item.status(), item.contentHash(), item.publishedAt(),
                mapper.findGeofenceVertices(site.organizationId(), site.id(), item.id()).stream()
                        .map(vertex -> new SpatialVertexResponse(
                                vertex.sequenceNumber(), vertex.x(), vertex.y()))
                        .toList(),
                item.createdAt(), item.updatedAt(), item.version());
    }

    private static SpatialDetectionPolicy policy(WaypointVersionRecord item) {
        return new SpatialDetectionPolicy(
                item.boundaryToleranceMeters(), item.enterConfirmPoints(),
                item.exitConfirmPoints(), item.dwellSeconds(),
                item.allowedLatenessSeconds(), item.idleFlushSeconds());
    }

    private static SpatialDetectionPolicy policy(GeofenceVersionRecord item) {
        return new SpatialDetectionPolicy(
                item.boundaryToleranceMeters(), item.enterConfirmPoints(),
                item.exitConfirmPoints(), item.dwellSeconds(),
                item.allowedLatenessSeconds(), item.idleFlushSeconds());
    }

    private MineSite requirePageAndSite(UUID siteId, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new MineException(PAGE_INVALID, Map.of("page", page, "size", size));
        }
        return authorizer.requireSite(siteId);
    }

    private void requireWaypoint(MineSite site, UUID waypointId) {
        mapper.findWaypointById(site.organizationId(), site.id(), waypointId)
                .orElseThrow(() -> notFound(WAYPOINT_NOT_FOUND, waypointId));
    }

    private void requireRoute(MineSite site, UUID routeId) {
        mapper.findRouteById(site.organizationId(), site.id(), routeId)
                .orElseThrow(() -> notFound(ROUTE_NOT_FOUND, routeId));
    }

    private void requireGeofence(MineSite site, UUID geofenceId) {
        mapper.findGeofenceById(site.organizationId(), site.id(), geofenceId)
                .orElseThrow(() -> notFound(GEOFENCE_NOT_FOUND, geofenceId));
    }

    private List<String> vehicleTypes(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JacksonException exception) {
            throw new IllegalStateException("路线适用车辆类型无法解析", exception);
        }
    }

    /** 将数据库聚合的有序顶点转换为接口结构。 */
    private List<SpatialVertexResponse> spatialVertices(String json) {
        try {
            return objectMapper.readValue(json, SPATIAL_VERTICES);
        } catch (JacksonException exception) {
            throw new IllegalStateException("空间区域顶点无法解析", exception);
        }
    }

    private MineException versionNotFound(UUID id) {
        return notFound(VERSION_NOT_FOUND, id);
    }

    private MineException notFound(ErrorCode errorCode, UUID id) {
        return new MineException(errorCode, Map.of("id", id));
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String optionalUpper(String value) {
        String normalized = optional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
