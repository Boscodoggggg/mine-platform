package com.bosco.mine.server.modules.mine.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.LocationTagRequest;
import com.bosco.mine.server.modules.mine.dto.LocationTagResponse;
import com.bosco.mine.server.modules.mine.dto.LocationTagUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.MineArchiveRequest;
import com.bosco.mine.server.modules.mine.dto.VehicleRequest;
import com.bosco.mine.server.modules.mine.dto.VehicleResponse;
import com.bosco.mine.server.modules.mine.dto.VehicleUpdateRequest;
import com.bosco.mine.server.modules.mine.service.MineAssetCommandService;
import com.bosco.mine.server.modules.mine.service.MineAssetQueryService;
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
import java.util.UUID;

/**
 * 提供矿区车辆、定位标签、坐标系和转换版本管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}")
public class MineAssetController {

    private final MineAssetCommandService commandService;
    private final MineAssetQueryService queryService;

    /**
     * 创建矿区资产控制器。
     *
     * @param commandService 资产写服务
     * @param queryService 资产查询服务
     */
    public MineAssetController(
            MineAssetCommandService commandService,
            MineAssetQueryService queryService
    ) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** 分页查询车辆。 */
    @GetMapping("/vehicles")
    @SaCheckPermission("mine:vehicle:view")
    public PageResponse<VehicleResponse> findVehicles(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false)
            @Pattern(regexp = "ACTIVE|MAINTENANCE|OUT_OF_SERVICE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findVehicles(siteId, keyword, status, page, size);
    }

    /** 创建车辆。 */
    @PostMapping("/vehicles")
    @SaCheckPermission("mine:vehicle:edit")
    public ResponseEntity<VehicleResponse> createVehicle(
            @PathVariable UUID siteId,
            @Valid @RequestBody VehicleRequest request
    ) {
        VehicleResponse created = commandService.createVehicle(siteId, request);
        return ResponseEntity.created(resourceUri(siteId, "vehicles", created.id())).body(created);
    }

    /** 修改车辆。 */
    @PatchMapping("/vehicles/{vehicleId}")
    @SaCheckPermission("mine:vehicle:edit")
    public VehicleResponse updateVehicle(
            @PathVariable UUID siteId,
            @PathVariable UUID vehicleId,
            @Valid @RequestBody VehicleUpdateRequest request
    ) {
        return commandService.updateVehicle(siteId, vehicleId, request);
    }

    /** 归档车辆。 */
    @PostMapping("/vehicles/{vehicleId}/archive")
    @SaCheckPermission("mine:vehicle:edit")
    public ResponseEntity<Void> archiveVehicle(
            @PathVariable UUID siteId,
            @PathVariable UUID vehicleId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        commandService.archiveVehicle(siteId, vehicleId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 分页查询定位标签。 */
    @GetMapping("/location-tags")
    @SaCheckPermission("mine:location-tag:view")
    public PageResponse<LocationTagResponse> findLocationTags(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false)
            @Pattern(regexp = "ACTIVE|LOST|RETIRED|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findLocationTags(siteId, keyword, status, page, size);
    }

    /** 创建定位标签。 */
    @PostMapping("/location-tags")
    @SaCheckPermission("mine:location-tag:edit")
    public ResponseEntity<LocationTagResponse> createLocationTag(
            @PathVariable UUID siteId,
            @Valid @RequestBody LocationTagRequest request
    ) {
        LocationTagResponse created = commandService.createLocationTag(siteId, request);
        return ResponseEntity.created(resourceUri(siteId, "location-tags", created.id())).body(created);
    }

    /** 修改定位标签。 */
    @PatchMapping("/location-tags/{tagId}")
    @SaCheckPermission("mine:location-tag:edit")
    public LocationTagResponse updateLocationTag(
            @PathVariable UUID siteId,
            @PathVariable UUID tagId,
            @Valid @RequestBody LocationTagUpdateRequest request
    ) {
        return commandService.updateLocationTag(siteId, tagId, request);
    }

    /** 归档定位标签。 */
    @PostMapping("/location-tags/{tagId}/archive")
    @SaCheckPermission("mine:location-tag:edit")
    public ResponseEntity<Void> archiveLocationTag(
            @PathVariable UUID siteId,
            @PathVariable UUID tagId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        commandService.archiveLocationTag(siteId, tagId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 分页查询坐标系。 */
    @GetMapping("/coordinate-systems")
    @SaCheckPermission("mine:coordinate:view")
    public PageResponse<CoordinateSystemResponse> findCoordinateSystems(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findCoordinateSystems(siteId, keyword, status, page, size);
    }

    /** 创建坐标系。 */
    @PostMapping("/coordinate-systems")
    @SaCheckPermission("mine:coordinate:edit")
    public ResponseEntity<CoordinateSystemResponse> createCoordinateSystem(
            @PathVariable UUID siteId,
            @Valid @RequestBody CoordinateSystemRequest request
    ) {
        CoordinateSystemResponse created = commandService.createCoordinateSystem(siteId, request);
        return ResponseEntity.created(resourceUri(siteId, "coordinate-systems", created.id())).body(created);
    }

    /** 修改坐标系。 */
    @PatchMapping("/coordinate-systems/{systemId}")
    @SaCheckPermission("mine:coordinate:edit")
    public CoordinateSystemResponse updateCoordinateSystem(
            @PathVariable UUID siteId,
            @PathVariable UUID systemId,
            @Valid @RequestBody CoordinateSystemUpdateRequest request
    ) {
        return commandService.updateCoordinateSystem(siteId, systemId, request);
    }

    /** 归档坐标系但保留历史引用。 */
    @PostMapping("/coordinate-systems/{systemId}/archive")
    @SaCheckPermission("mine:coordinate:edit")
    public ResponseEntity<Void> archiveCoordinateSystem(
            @PathVariable UUID siteId,
            @PathVariable UUID systemId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        commandService.archiveCoordinateSystem(siteId, systemId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 分页查询坐标转换版本。 */
    @GetMapping("/coordinate-transforms")
    @SaCheckPermission("mine:coordinate:view")
    public PageResponse<CoordinateTransformResponse> findCoordinateTransforms(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Pattern(regexp = "DRAFT|PUBLISHED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findCoordinateTransforms(siteId, status, page, size);
    }

    /** 创建坐标转换草稿。 */
    @PostMapping("/coordinate-transforms")
    @SaCheckPermission("mine:coordinate:edit")
    public ResponseEntity<CoordinateTransformResponse> createCoordinateTransform(
            @PathVariable UUID siteId,
            @Valid @RequestBody CoordinateTransformRequest request
    ) {
        CoordinateTransformResponse created = commandService.createCoordinateTransform(siteId, request);
        return ResponseEntity.created(resourceUri(siteId, "coordinate-transforms", created.id())).body(created);
    }

    /** 修改坐标转换草稿。 */
    @PatchMapping("/coordinate-transforms/{transformId}")
    @SaCheckPermission("mine:coordinate:edit")
    public CoordinateTransformResponse updateCoordinateTransform(
            @PathVariable UUID siteId,
            @PathVariable UUID transformId,
            @Valid @RequestBody CoordinateTransformUpdateRequest request
    ) {
        return commandService.updateCoordinateTransform(siteId, transformId, request);
    }

    /** 发布并永久锁定坐标转换版本。 */
    @PostMapping("/coordinate-transforms/{transformId}/publish")
    @SaCheckPermission("mine:coordinate:publish")
    public CoordinateTransformResponse publishCoordinateTransform(
            @PathVariable UUID siteId,
            @PathVariable UUID transformId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        return commandService.publishCoordinateTransform(siteId, transformId, request.version());
    }

    private URI resourceUri(UUID siteId, String collection, UUID resourceId) {
        return UriComponentsBuilder.fromPath("/api/v1/mine/sites/{siteId}/{collection}/{resourceId}")
                .buildAndExpand(siteId, collection, resourceId)
                .toUri();
    }
}
