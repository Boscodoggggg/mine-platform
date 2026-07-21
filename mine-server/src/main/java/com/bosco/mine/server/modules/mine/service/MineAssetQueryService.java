package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformResponse;
import com.bosco.mine.server.modules.mine.dto.LocationTagResponse;
import com.bosco.mine.server.modules.mine.dto.VehicleResponse;
import com.bosco.mine.server.modules.mine.model.MineCoordinateSystem;
import com.bosco.mine.server.modules.mine.model.MineCoordinateTransform;
import com.bosco.mine.server.modules.mine.model.MineLocationTag;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.model.MineVehicle;
import com.bosco.mine.server.modules.mine.repository.MineAssetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.UUID;

/**
 * 按服务端矿区作用域分页查询车辆、标签和双坐标主数据。
 */
@Service
public class MineAssetQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final ErrorCode PAGE_INVALID =
            new ErrorCode("MINE-ASSET-422-001", "分页参数不合法");

    private final MineAssetMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;

    /**
     * 创建矿区资产查询服务。
     *
     * @param mapper 资产数据访问接口
     * @param authorizer 矿区数据作用域授权器
     * @param objectMapper JSON 映射器
     */
    public MineAssetQueryService(
            MineAssetMapper mapper,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.objectMapper = objectMapper;
    }

    /** 分页查询矿区车辆。 */
    @Transactional(readOnly = true)
    public PageResponse<VehicleResponse> findVehicles(
            UUID siteId, String keyword, String status, int page, int size
    ) {
        MineSite site = requirePageAndSite(siteId, page, size);
        String normalizedKeyword = optional(keyword);
        String normalizedStatus = optionalUpper(status);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                mapper.findVehicles(
                                site.organizationId(), site.id(), normalizedKeyword,
                                normalizedStatus, offset, size)
                        .stream().map(MineAssetQueryService::toResponse).toList(),
                page,
                size,
                mapper.countVehicles(
                        site.organizationId(), site.id(), normalizedKeyword, normalizedStatus));
    }

    /** 分页查询矿区定位标签。 */
    @Transactional(readOnly = true)
    public PageResponse<LocationTagResponse> findLocationTags(
            UUID siteId, String keyword, String status, int page, int size
    ) {
        MineSite site = requirePageAndSite(siteId, page, size);
        String normalizedKeyword = optional(keyword);
        String normalizedStatus = optionalUpper(status);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                mapper.findLocationTags(
                                site.organizationId(), site.id(), normalizedKeyword,
                                normalizedStatus, offset, size)
                        .stream().map(MineAssetQueryService::toResponse).toList(),
                page,
                size,
                mapper.countLocationTags(
                        site.organizationId(), site.id(), normalizedKeyword, normalizedStatus));
    }

    /** 分页查询矿区坐标系。 */
    @Transactional(readOnly = true)
    public PageResponse<CoordinateSystemResponse> findCoordinateSystems(
            UUID siteId, String keyword, String status, int page, int size
    ) {
        MineSite site = requirePageAndSite(siteId, page, size);
        String normalizedKeyword = optional(keyword);
        String normalizedStatus = optionalUpper(status);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                mapper.findCoordinateSystems(
                                site.organizationId(), site.id(), normalizedKeyword,
                                normalizedStatus, offset, size)
                        .stream().map(MineAssetQueryService::toResponse).toList(),
                page,
                size,
                mapper.countCoordinateSystems(
                        site.organizationId(), site.id(), normalizedKeyword, normalizedStatus));
    }

    /** 分页查询矿区坐标转换版本。 */
    @Transactional(readOnly = true)
    public PageResponse<CoordinateTransformResponse> findCoordinateTransforms(
            UUID siteId, String status, int page, int size
    ) {
        MineSite site = requirePageAndSite(siteId, page, size);
        String normalizedStatus = optionalUpper(status);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                mapper.findCoordinateTransforms(
                                site.organizationId(), site.id(), normalizedStatus, offset, size)
                        .stream().map(this::toResponse).toList(),
                page,
                size,
                mapper.countCoordinateTransforms(
                        site.organizationId(), site.id(), normalizedStatus));
    }

    /** 将车辆持久化快照转换为响应。 */
    public static VehicleResponse toResponse(MineVehicle vehicle) {
        return new VehicleResponse(
                vehicle.id(), vehicle.organizationId(), vehicle.siteId(), vehicle.vehicleCode(),
                vehicle.name(), vehicle.vehicleType(), vehicle.mineNumber(), vehicle.licensePlate(),
                vehicle.brand(), vehicle.model(), vehicle.ratedLoadTons(), vehicle.status(),
                vehicle.archivedAt(), vehicle.createdAt(), vehicle.updatedAt(), vehicle.version());
    }

    /** 将定位标签持久化快照转换为响应。 */
    public static LocationTagResponse toResponse(MineLocationTag tag) {
        return new LocationTagResponse(
                tag.id(), tag.organizationId(), tag.siteId(), tag.tagCode(), tag.vendor(), tag.model(),
                tag.batteryPercent(), tag.batteryObservedAt(), tag.status(), tag.archivedAt(),
                tag.createdAt(), tag.updatedAt(), tag.version());
    }

    /** 将坐标系持久化快照转换为响应。 */
    public static CoordinateSystemResponse toResponse(MineCoordinateSystem system) {
        return new CoordinateSystemResponse(
                system.id(), system.organizationId(), system.siteId(), system.systemCode(),
                system.name(), system.coordinateType(), system.unit(), system.epsgCode(),
                system.unified(), system.minX(), system.maxX(), system.minY(), system.maxY(),
                system.minZ(), system.maxZ(), system.status(), system.archivedAt(),
                system.createdAt(), system.updatedAt(), system.version());
    }

    /** 将坐标转换持久化快照转换为响应。 */
    public CoordinateTransformResponse toResponse(MineCoordinateTransform transform) {
        return new CoordinateTransformResponse(
                transform.id(), transform.organizationId(), transform.siteId(),
                transform.sourceCoordinateSystemId(), transform.targetCoordinateSystemId(),
                transform.versionNumber(), transform.algorithm(), readJson(transform.parametersJson()),
                transform.validFrom(), transform.validTo(), transform.status(), transform.contentHash(),
                transform.publishedAt(), transform.createdAt(), transform.updatedAt(), transform.version());
    }

    private MineSite requirePageAndSite(UUID siteId, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new MineException(PAGE_INVALID, java.util.Map.of("page", page, "size", size));
        }
        return authorizer.requireSite(siteId);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("坐标转换参数无法解析", exception);
        }
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String optionalUpper(String value) {
        String normalized = optional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
