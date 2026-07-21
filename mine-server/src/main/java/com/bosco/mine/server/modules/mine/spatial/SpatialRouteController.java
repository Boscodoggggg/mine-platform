package com.bosco.mine.server.modules.mine.spatial;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.dto.MineArchiveRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.CurrentSpatialRegionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionUpdateRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionUpdateRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialObjectRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialObjectResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 提供途径点、路线和电子围栏的矿区级版本管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}")
public class SpatialRouteController {

    private final SpatialRouteCommandService commandService;
    private final SpatialRouteQueryService queryService;

    /**
     * 创建空间路线控制器。
     *
     * @param commandService 空间主数据命令服务
     * @param queryService 空间主数据查询服务
     */
    public SpatialRouteController(
            SpatialRouteCommandService commandService,
            SpatialRouteQueryService queryService
    ) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** 查询当前生效的途径点和电子围栏聚合快照。 */
    @GetMapping("/spatial-regions/current")
    @SaCheckPermission("mine:spatial:view")
    public CurrentSpatialRegionResponse findCurrentSpatialRegions(@PathVariable UUID siteId) {
        return queryService.findCurrentSpatialRegions(siteId);
    }

    /** 分页查询途径点稳定对象。 */
    @GetMapping("/waypoints")
    @SaCheckPermission("mine:spatial:view")
    public PageResponse<SpatialObjectResponse> findWaypoints(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findWaypoints(siteId, keyword, status, page, size);
    }

    /** 创建途径点稳定对象。 */
    @PostMapping("/waypoints")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<SpatialObjectResponse> createWaypoint(
            @PathVariable UUID siteId,
            @Valid @RequestBody SpatialObjectRequest request
    ) {
        SpatialObjectResponse created = commandService.createWaypoint(siteId, request.code());
        return ResponseEntity.created(objectUri(siteId, "waypoints", created.id())).body(created);
    }

    /** 查询途径点稳定对象。 */
    @GetMapping("/waypoints/{waypointId}")
    @SaCheckPermission("mine:spatial:view")
    public SpatialObjectResponse findWaypoint(
            @PathVariable UUID siteId,
            @PathVariable UUID waypointId
    ) {
        return queryService.findWaypoint(siteId, waypointId);
    }

    /** 归档途径点稳定对象并保留版本历史。 */
    @PostMapping("/waypoints/{waypointId}/archive")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<Void> archiveWaypoint(
            @PathVariable UUID siteId,
            @PathVariable UUID waypointId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        commandService.archiveWaypoint(siteId, waypointId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 查询途径点全部版本。 */
    @GetMapping("/waypoints/{waypointId}/versions")
    @SaCheckPermission("mine:spatial:view")
    public List<WaypointVersionResponse> findWaypointVersions(
            @PathVariable UUID siteId,
            @PathVariable UUID waypointId
    ) {
        return queryService.findWaypointVersions(siteId, waypointId);
    }

    /** 创建途径点草稿版本。 */
    @PostMapping("/waypoints/{waypointId}/versions")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<WaypointVersionResponse> createWaypointVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID waypointId,
            @Valid @RequestBody WaypointVersionRequest request
    ) {
        WaypointVersionResponse created = commandService.createWaypointVersion(
                siteId, waypointId, request);
        return ResponseEntity.created(versionUri(
                siteId, "waypoints", waypointId, created.id())).body(created);
    }

    /** 查询指定途径点版本。 */
    @GetMapping("/waypoints/{waypointId}/versions/{versionId}")
    @SaCheckPermission("mine:spatial:view")
    public WaypointVersionResponse findWaypointVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID waypointId,
            @PathVariable UUID versionId
    ) {
        return queryService.findWaypointVersion(siteId, waypointId, versionId);
    }

    /** 修改途径点草稿版本。 */
    @PatchMapping("/waypoints/{waypointId}/versions/{versionId}")
    @SaCheckPermission("mine:spatial:edit")
    public WaypointVersionResponse updateWaypointVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID waypointId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WaypointVersionUpdateRequest request
    ) {
        return commandService.updateWaypointVersion(siteId, waypointId, versionId, request);
    }

    /** 发布并永久锁定途径点版本。 */
    @PostMapping("/waypoints/{waypointId}/versions/{versionId}/publish")
    @SaCheckPermission("mine:spatial:publish")
    public WaypointVersionResponse publishWaypointVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID waypointId,
            @PathVariable UUID versionId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        return commandService.publishWaypointVersion(
                siteId, waypointId, versionId, request.version());
    }

    /** 分页查询路线稳定对象。 */
    @GetMapping("/routes")
    @SaCheckPermission("mine:spatial:view")
    public PageResponse<SpatialObjectResponse> findRoutes(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findRoutes(siteId, keyword, status, page, size);
    }

    /** 创建路线稳定对象。 */
    @PostMapping("/routes")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<SpatialObjectResponse> createRoute(
            @PathVariable UUID siteId,
            @Valid @RequestBody SpatialObjectRequest request
    ) {
        SpatialObjectResponse created = commandService.createRoute(siteId, request.code());
        return ResponseEntity.created(objectUri(siteId, "routes", created.id())).body(created);
    }

    /** 查询路线稳定对象。 */
    @GetMapping("/routes/{routeId}")
    @SaCheckPermission("mine:spatial:view")
    public SpatialObjectResponse findRoute(
            @PathVariable UUID siteId,
            @PathVariable UUID routeId
    ) {
        return queryService.findRoute(siteId, routeId);
    }

    /** 归档路线稳定对象并保留版本历史。 */
    @PostMapping("/routes/{routeId}/archive")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<Void> archiveRoute(
            @PathVariable UUID siteId,
            @PathVariable UUID routeId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        commandService.archiveRoute(siteId, routeId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 查询路线全部版本。 */
    @GetMapping("/routes/{routeId}/versions")
    @SaCheckPermission("mine:spatial:view")
    public List<RouteVersionResponse> findRouteVersions(
            @PathVariable UUID siteId,
            @PathVariable UUID routeId
    ) {
        return queryService.findRouteVersions(siteId, routeId);
    }

    /** 创建路线草稿版本。 */
    @PostMapping("/routes/{routeId}/versions")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<RouteVersionResponse> createRouteVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID routeId,
            @Valid @RequestBody RouteVersionRequest request
    ) {
        RouteVersionResponse created = commandService.createRouteVersion(siteId, routeId, request);
        return ResponseEntity.created(versionUri(
                siteId, "routes", routeId, created.id())).body(created);
    }

    /** 查询指定路线版本。 */
    @GetMapping("/routes/{routeId}/versions/{versionId}")
    @SaCheckPermission("mine:spatial:view")
    public RouteVersionResponse findRouteVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID routeId,
            @PathVariable UUID versionId
    ) {
        return queryService.findRouteVersion(siteId, routeId, versionId);
    }

    /** 修改路线草稿版本。 */
    @PatchMapping("/routes/{routeId}/versions/{versionId}")
    @SaCheckPermission("mine:spatial:edit")
    public RouteVersionResponse updateRouteVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID routeId,
            @PathVariable UUID versionId,
            @Valid @RequestBody RouteVersionUpdateRequest request
    ) {
        return commandService.updateRouteVersion(siteId, routeId, versionId, request);
    }

    /** 发布并永久锁定路线版本。 */
    @PostMapping("/routes/{routeId}/versions/{versionId}/publish")
    @SaCheckPermission("mine:spatial:publish")
    public RouteVersionResponse publishRouteVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID routeId,
            @PathVariable UUID versionId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        return commandService.publishRouteVersion(siteId, routeId, versionId, request.version());
    }

    /** 分页查询电子围栏稳定对象。 */
    @GetMapping("/geofences")
    @SaCheckPermission("mine:spatial:view")
    public PageResponse<SpatialObjectResponse> findGeofences(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findGeofences(siteId, keyword, status, page, size);
    }

    /** 创建电子围栏稳定对象。 */
    @PostMapping("/geofences")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<SpatialObjectResponse> createGeofence(
            @PathVariable UUID siteId,
            @Valid @RequestBody SpatialObjectRequest request
    ) {
        SpatialObjectResponse created = commandService.createGeofence(siteId, request.code());
        return ResponseEntity.created(objectUri(siteId, "geofences", created.id())).body(created);
    }

    /** 查询电子围栏稳定对象。 */
    @GetMapping("/geofences/{geofenceId}")
    @SaCheckPermission("mine:spatial:view")
    public SpatialObjectResponse findGeofence(
            @PathVariable UUID siteId,
            @PathVariable UUID geofenceId
    ) {
        return queryService.findGeofence(siteId, geofenceId);
    }

    /** 归档电子围栏稳定对象并保留版本历史。 */
    @PostMapping("/geofences/{geofenceId}/archive")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<Void> archiveGeofence(
            @PathVariable UUID siteId,
            @PathVariable UUID geofenceId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        commandService.archiveGeofence(siteId, geofenceId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 查询电子围栏全部版本。 */
    @GetMapping("/geofences/{geofenceId}/versions")
    @SaCheckPermission("mine:spatial:view")
    public List<GeofenceVersionResponse> findGeofenceVersions(
            @PathVariable UUID siteId,
            @PathVariable UUID geofenceId
    ) {
        return queryService.findGeofenceVersions(siteId, geofenceId);
    }

    /** 创建电子围栏草稿版本。 */
    @PostMapping("/geofences/{geofenceId}/versions")
    @SaCheckPermission("mine:spatial:edit")
    public ResponseEntity<GeofenceVersionResponse> createGeofenceVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID geofenceId,
            @Valid @RequestBody GeofenceVersionRequest request
    ) {
        GeofenceVersionResponse created = commandService.createGeofenceVersion(
                siteId, geofenceId, request);
        return ResponseEntity.created(versionUri(
                siteId, "geofences", geofenceId, created.id())).body(created);
    }

    /** 查询指定电子围栏版本。 */
    @GetMapping("/geofences/{geofenceId}/versions/{versionId}")
    @SaCheckPermission("mine:spatial:view")
    public GeofenceVersionResponse findGeofenceVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID geofenceId,
            @PathVariable UUID versionId
    ) {
        return queryService.findGeofenceVersion(siteId, geofenceId, versionId);
    }

    /** 修改电子围栏草稿版本。 */
    @PatchMapping("/geofences/{geofenceId}/versions/{versionId}")
    @SaCheckPermission("mine:spatial:edit")
    public GeofenceVersionResponse updateGeofenceVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID geofenceId,
            @PathVariable UUID versionId,
            @Valid @RequestBody GeofenceVersionUpdateRequest request
    ) {
        return commandService.updateGeofenceVersion(siteId, geofenceId, versionId, request);
    }

    /** 发布并永久锁定电子围栏版本。 */
    @PostMapping("/geofences/{geofenceId}/versions/{versionId}/publish")
    @SaCheckPermission("mine:spatial:publish")
    public GeofenceVersionResponse publishGeofenceVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID geofenceId,
            @PathVariable UUID versionId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        return commandService.publishGeofenceVersion(
                siteId, geofenceId, versionId, request.version());
    }

    private URI objectUri(UUID siteId, String collection, UUID objectId) {
        return UriComponentsBuilder
                .fromPath("/api/v1/mine/sites/{siteId}/{collection}/{objectId}")
                .buildAndExpand(siteId, collection, objectId)
                .toUri();
    }

    private URI versionUri(
            UUID siteId,
            String collection,
            UUID objectId,
            UUID versionId
    ) {
        return UriComponentsBuilder
                .fromPath("/api/v1/mine/sites/{siteId}/{collection}/{objectId}/versions/{versionId}")
                .buildAndExpand(siteId, collection, objectId, versionId)
                .toUri();
    }
}
