package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformResponse;
import com.bosco.mine.server.modules.mine.dto.CoordinateTransformUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.LocationTagRequest;
import com.bosco.mine.server.modules.mine.dto.LocationTagResponse;
import com.bosco.mine.server.modules.mine.dto.LocationTagUpdateRequest;
import com.bosco.mine.server.modules.mine.dto.VehicleRequest;
import com.bosco.mine.server.modules.mine.dto.VehicleResponse;
import com.bosco.mine.server.modules.mine.dto.VehicleUpdateRequest;
import com.bosco.mine.server.modules.mine.model.MineCoordinateSystem;
import com.bosco.mine.server.modules.mine.model.MineCoordinateTransform;
import com.bosco.mine.server.modules.mine.model.MineLocationTag;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.model.MineVehicle;
import com.bosco.mine.server.modules.mine.repository.MineAssetMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 管理矿区车辆、定位标签、坐标系和不可变坐标转换版本。
 */
@Service
public class MineAssetCommandService {

    private static final ErrorCode ASSET_INVALID =
            new ErrorCode("MINE-ASSET-422-002", "矿区资产或坐标参数不合法");
    private static final ErrorCode COORDINATE_SCOPE_MISMATCH =
            new ErrorCode("MINE-ASSET-422-003", "坐标系不属于当前矿区或不可用");
    private static final ErrorCode VEHICLE_NOT_FOUND =
            new ErrorCode("MINE-ASSET-404-001", "车辆不存在或不可用");
    private static final ErrorCode TAG_NOT_FOUND =
            new ErrorCode("MINE-ASSET-404-002", "定位标签不存在或不可用");
    private static final ErrorCode COORDINATE_SYSTEM_NOT_FOUND =
            new ErrorCode("MINE-ASSET-404-003", "坐标系不存在或不可用");
    private static final ErrorCode COORDINATE_TRANSFORM_NOT_FOUND =
            new ErrorCode("MINE-ASSET-404-004", "坐标转换版本不存在或不可用");
    private static final ErrorCode VEHICLE_CODE_CONFLICT =
            new ErrorCode("MINE-ASSET-409-001", "车辆编码或矿山编号已存在");
    private static final ErrorCode VEHICLE_VERSION_CONFLICT =
            new ErrorCode("MINE-ASSET-409-002", "车辆已被其他管理员修改");
    private static final ErrorCode TAG_CODE_CONFLICT =
            new ErrorCode("MINE-ASSET-409-003", "定位标签编码已存在");
    private static final ErrorCode TAG_VERSION_CONFLICT =
            new ErrorCode("MINE-ASSET-409-004", "定位标签已被其他管理员修改");
    private static final ErrorCode COORDINATE_SYSTEM_CONFLICT =
            new ErrorCode("MINE-ASSET-409-005", "坐标系冲突、被引用或已被其他管理员修改");
    private static final ErrorCode COORDINATE_TRANSFORM_CONFLICT =
            new ErrorCode("MINE-ASSET-409-006", "坐标转换版本冲突、已发布或已被修改");

    private final MineAssetMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final MineAssetQueryService queryService;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建矿区资产写服务。
     *
     * @param mapper 资产数据访问接口
     * @param authorizer 矿区数据作用域授权器
     * @param queryService 资产查询服务
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public MineAssetCommandService(
            MineAssetMapper mapper,
            MineScopeAuthorizer authorizer,
            MineAssetQueryService queryService,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.queryService = queryService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 创建矿区车辆。 */
    @Transactional
    public VehicleResponse createVehicle(UUID siteId, VehicleRequest request) {
        MineSite site = authorizer.requireSite(siteId);
        UUID id = idGenerator.next();
        UUID operatorId = authorizer.currentUserId();
        try {
            mapper.insertVehicle(
                    id, site.organizationId(), site.id(), normalizeCode(request.vehicleCode()),
                    required(request.name()), upper(request.vehicleType()), optional(request.mineNumber()),
                    optional(request.licensePlate()), optional(request.brand()), optional(request.model()),
                    request.ratedLoadTons(), operatorId);
        } catch (DuplicateKeyException exception) {
            throw new MineException(VEHICLE_CODE_CONFLICT, Map.of());
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        VehicleResponse created = vehicle(site, id);
        audit("CREATE_VEHICLE", "mine:vehicle:edit", "MINE_VEHICLE", id,
                Map.of(), vehicleSummary(created));
        return created;
    }

    /** 使用乐观锁修改车辆。 */
    @Transactional
    public VehicleResponse updateVehicle(UUID siteId, UUID vehicleId, VehicleUpdateRequest request) {
        MineSite site = authorizer.requireSite(siteId);
        MineVehicle before = requireVehicle(site, vehicleId);
        UUID operatorId = authorizer.currentUserId();
        try {
            int changed = mapper.updateVehicle(
                    site.organizationId(), site.id(), vehicleId, required(request.name()),
                    upper(request.vehicleType()), optional(request.mineNumber()),
                    optional(request.licensePlate()), optional(request.brand()), optional(request.model()),
                    request.ratedLoadTons(), request.version(), operatorId);
            if (changed == 0) throw vehicleConflict(vehicleId);
        } catch (DuplicateKeyException exception) {
            throw new MineException(VEHICLE_CODE_CONFLICT, Map.of("vehicleId", vehicleId));
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        VehicleResponse updated = vehicle(site, vehicleId);
        audit("UPDATE_VEHICLE", "mine:vehicle:edit", "MINE_VEHICLE", vehicleId,
                vehicleSummary(MineAssetQueryService.toResponse(before)), vehicleSummary(updated));
        return updated;
    }

    /** 使用乐观锁归档车辆。 */
    @Transactional
    public void archiveVehicle(UUID siteId, UUID vehicleId, long version) {
        MineSite site = authorizer.requireSite(siteId);
        MineVehicle before = requireVehicle(site, vehicleId);
        if (mapper.archiveVehicle(
                site.organizationId(), site.id(), vehicleId, version,
                authorizer.currentUserId()) == 0) {
            throw vehicleConflict(vehicleId);
        }
        VehicleResponse archived = vehicle(site, vehicleId);
        audit("ARCHIVE_VEHICLE", "mine:vehicle:edit", "MINE_VEHICLE", vehicleId,
                vehicleSummary(MineAssetQueryService.toResponse(before)), vehicleSummary(archived));
    }

    /** 创建定位标签。 */
    @Transactional
    public LocationTagResponse createLocationTag(UUID siteId, LocationTagRequest request) {
        MineSite site = authorizer.requireSite(siteId);
        UUID id = idGenerator.next();
        UUID operatorId = authorizer.currentUserId();
        try {
            mapper.insertLocationTag(
                    id, site.organizationId(), site.id(), normalizeCode(request.tagCode()),
                    optional(request.vendor()), optional(request.model()), request.batteryPercent(),
                    request.batteryObservedAt(), operatorId);
        } catch (DuplicateKeyException exception) {
            throw new MineException(TAG_CODE_CONFLICT, Map.of());
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        LocationTagResponse created = locationTag(site, id);
        audit("CREATE_LOCATION_TAG", "mine:location-tag:edit", "MINE_LOCATION_TAG", id,
                Map.of(), tagSummary(created));
        return created;
    }

    /** 使用乐观锁修改定位标签。 */
    @Transactional
    public LocationTagResponse updateLocationTag(
            UUID siteId, UUID tagId, LocationTagUpdateRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        MineLocationTag before = requireLocationTag(site, tagId);
        try {
            int changed = mapper.updateLocationTag(
                    site.organizationId(), site.id(), tagId, optional(request.vendor()),
                    optional(request.model()), request.batteryPercent(), request.batteryObservedAt(),
                    request.version(), authorizer.currentUserId());
            if (changed == 0) throw tagConflict(tagId);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        LocationTagResponse updated = locationTag(site, tagId);
        audit("UPDATE_LOCATION_TAG", "mine:location-tag:edit", "MINE_LOCATION_TAG", tagId,
                tagSummary(MineAssetQueryService.toResponse(before)), tagSummary(updated));
        return updated;
    }

    /** 使用乐观锁归档定位标签。 */
    @Transactional
    public void archiveLocationTag(UUID siteId, UUID tagId, long version) {
        MineSite site = authorizer.requireSite(siteId);
        MineLocationTag before = requireLocationTag(site, tagId);
        if (mapper.archiveLocationTag(
                site.organizationId(), site.id(), tagId, version,
                authorizer.currentUserId()) == 0) {
            throw tagConflict(tagId);
        }
        LocationTagResponse archived = locationTag(site, tagId);
        audit("ARCHIVE_LOCATION_TAG", "mine:location-tag:edit", "MINE_LOCATION_TAG", tagId,
                tagSummary(MineAssetQueryService.toResponse(before)), tagSummary(archived));
    }

    /** 创建坐标系。 */
    @Transactional
    public CoordinateSystemResponse createCoordinateSystem(
            UUID siteId, CoordinateSystemRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        UUID id = idGenerator.next();
        try {
            mapper.insertCoordinateSystem(
                    id, site.organizationId(), site.id(), normalizeCode(request.systemCode()),
                    required(request.name()), upper(request.coordinateType()), upper(request.unit()),
                    request.epsgCode(), Boolean.TRUE.equals(request.unified()),
                    requiredNumber(request.minX()), requiredNumber(request.maxX()),
                    requiredNumber(request.minY()), requiredNumber(request.maxY()),
                    request.minZ(), request.maxZ(), authorizer.currentUserId());
        } catch (DataIntegrityViolationException exception) {
            throw new MineException(COORDINATE_SYSTEM_CONFLICT, Map.of("siteId", site.id()));
        }
        CoordinateSystemResponse created = coordinateSystem(site, id);
        audit("CREATE_COORDINATE_SYSTEM", "mine:coordinate:edit", "MINE_COORDINATE_SYSTEM", id,
                Map.of(), coordinateSummary(created));
        return created;
    }

    /** 使用乐观锁修改坐标系。 */
    @Transactional
    public CoordinateSystemResponse updateCoordinateSystem(
            UUID siteId, UUID systemId, CoordinateSystemUpdateRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        MineCoordinateSystem before = requireCoordinateSystem(site, systemId, false);
        try {
            int changed = mapper.updateCoordinateSystem(
                    site.organizationId(), site.id(), systemId, required(request.name()),
                    upper(request.coordinateType()), upper(request.unit()), request.epsgCode(),
                    Boolean.TRUE.equals(request.unified()), requiredNumber(request.minX()),
                    requiredNumber(request.maxX()), requiredNumber(request.minY()),
                    requiredNumber(request.maxY()), request.minZ(), request.maxZ(),
                    request.version(), authorizer.currentUserId());
            if (changed == 0) throw coordinateConflict(systemId);
        } catch (DataIntegrityViolationException exception) {
            throw coordinateConflict(systemId);
        }
        CoordinateSystemResponse updated = coordinateSystem(site, systemId);
        audit("UPDATE_COORDINATE_SYSTEM", "mine:coordinate:edit", "MINE_COORDINATE_SYSTEM", systemId,
                coordinateSummary(MineAssetQueryService.toResponse(before)), coordinateSummary(updated));
        return updated;
    }

    /** 使用乐观锁归档坐标系，保留历史转换引用。 */
    @Transactional
    public void archiveCoordinateSystem(UUID siteId, UUID systemId, long version) {
        MineSite site = authorizer.requireSite(siteId);
        MineCoordinateSystem before = requireCoordinateSystem(site, systemId, false);
        if (mapper.archiveCoordinateSystem(
                site.organizationId(), site.id(), systemId, version,
                authorizer.currentUserId()) == 0) {
            throw coordinateConflict(systemId);
        }
        CoordinateSystemResponse archived = coordinateSystem(site, systemId);
        audit("ARCHIVE_COORDINATE_SYSTEM", "mine:coordinate:edit", "MINE_COORDINATE_SYSTEM", systemId,
                coordinateSummary(MineAssetQueryService.toResponse(before)), coordinateSummary(archived));
    }

    /** 创建坐标转换草稿。 */
    @Transactional
    public CoordinateTransformResponse createCoordinateTransform(
            UUID siteId, CoordinateTransformRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireCoordinateSystem(site, request.sourceCoordinateSystemId(), true);
        requireCoordinateSystem(site, request.targetCoordinateSystemId(), true);
        UUID id = idGenerator.next();
        try {
            mapper.insertCoordinateTransform(
                    id, site.organizationId(), site.id(), request.sourceCoordinateSystemId(),
                    request.targetCoordinateSystemId(), request.versionNumber(), upper(request.algorithm()),
                    parametersJson(request.parameters()), request.validFrom(), request.validTo(),
                    authorizer.currentUserId());
        } catch (DataIntegrityViolationException exception) {
            throw new MineException(COORDINATE_TRANSFORM_CONFLICT, Map.of("siteId", site.id()));
        }
        CoordinateTransformResponse created = coordinateTransform(site, id);
        audit("CREATE_COORDINATE_TRANSFORM", "mine:coordinate:edit",
                "MINE_COORDINATE_TRANSFORM", id, Map.of(), transformSummary(created));
        return created;
    }

    /** 使用乐观锁修改坐标转换草稿。 */
    @Transactional
    public CoordinateTransformResponse updateCoordinateTransform(
            UUID siteId, UUID transformId, CoordinateTransformUpdateRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        MineCoordinateTransform before = requireCoordinateTransform(site, transformId);
        if (!"DRAFT".equals(before.status())) throw transformConflict(transformId);
        try {
            int changed = mapper.updateCoordinateTransform(
                    site.organizationId(), site.id(), transformId, upper(request.algorithm()),
                    parametersJson(request.parameters()), request.validFrom(), request.validTo(),
                    request.version(), authorizer.currentUserId());
            if (changed == 0) throw transformConflict(transformId);
        } catch (DataIntegrityViolationException exception) {
            throw transformConflict(transformId);
        }
        CoordinateTransformResponse updated = coordinateTransform(site, transformId);
        audit("UPDATE_COORDINATE_TRANSFORM", "mine:coordinate:edit",
                "MINE_COORDINATE_TRANSFORM", transformId,
                transformSummary(queryService.toResponse(before)), transformSummary(updated));
        return updated;
    }

    /** 使用乐观锁发布并永久锁定坐标转换版本。 */
    @Transactional
    public CoordinateTransformResponse publishCoordinateTransform(
            UUID siteId, UUID transformId, long version
    ) {
        MineSite site = authorizer.requireSite(siteId);
        MineCoordinateTransform before = requireCoordinateTransform(site, transformId);
        if (!"DRAFT".equals(before.status()) || before.version() != version) {
            throw transformConflict(transformId);
        }
        requireCoordinateSystem(site, before.sourceCoordinateSystemId(), true);
        requireCoordinateSystem(site, before.targetCoordinateSystemId(), true);
        String contentHash = contentHash(before);
        try {
            if (mapper.publishCoordinateTransform(
                    site.organizationId(), site.id(), transformId, contentHash, version,
                    authorizer.currentUserId()) == 0) {
                throw transformConflict(transformId);
            }
        } catch (DataIntegrityViolationException exception) {
            throw transformConflict(transformId);
        }
        CoordinateTransformResponse published = coordinateTransform(site, transformId);
        audit("PUBLISH_COORDINATE_TRANSFORM", "mine:coordinate:publish",
                "MINE_COORDINATE_TRANSFORM", transformId,
                transformSummary(queryService.toResponse(before)), transformSummary(published));
        return published;
    }

    private MineVehicle requireVehicle(MineSite site, UUID id) {
        return mapper.findVehicleById(site.organizationId(), site.id(), id)
                .filter(item -> !"ARCHIVED".equals(item.status()))
                .orElseThrow(() -> new MineException(VEHICLE_NOT_FOUND, Map.of()));
    }

    private MineLocationTag requireLocationTag(MineSite site, UUID id) {
        return mapper.findLocationTagById(site.organizationId(), site.id(), id)
                .filter(item -> !"ARCHIVED".equals(item.status()))
                .orElseThrow(() -> new MineException(TAG_NOT_FOUND, Map.of()));
    }

    private MineCoordinateSystem requireCoordinateSystem(
            MineSite site, UUID id, boolean requireActive
    ) {
        return mapper.findCoordinateSystemById(site.organizationId(), site.id(), id)
                .filter(item -> !requireActive || "ACTIVE".equals(item.status()))
                .orElseThrow(() -> requireActive
                        ? new MineException(COORDINATE_SCOPE_MISMATCH, Map.of())
                        : new MineException(COORDINATE_SYSTEM_NOT_FOUND, Map.of()));
    }

    private MineCoordinateTransform requireCoordinateTransform(MineSite site, UUID id) {
        return mapper.findCoordinateTransformById(site.organizationId(), site.id(), id)
                .orElseThrow(() -> new MineException(COORDINATE_TRANSFORM_NOT_FOUND, Map.of()));
    }

    private VehicleResponse vehicle(MineSite site, UUID id) {
        return mapper.findVehicleById(site.organizationId(), site.id(), id)
                .map(MineAssetQueryService::toResponse)
                .orElseThrow(() -> new MineException(VEHICLE_NOT_FOUND, Map.of()));
    }

    private LocationTagResponse locationTag(MineSite site, UUID id) {
        return mapper.findLocationTagById(site.organizationId(), site.id(), id)
                .map(MineAssetQueryService::toResponse)
                .orElseThrow(() -> new MineException(TAG_NOT_FOUND, Map.of()));
    }

    private CoordinateSystemResponse coordinateSystem(MineSite site, UUID id) {
        return mapper.findCoordinateSystemById(site.organizationId(), site.id(), id)
                .map(MineAssetQueryService::toResponse)
                .orElseThrow(() -> new MineException(COORDINATE_SYSTEM_NOT_FOUND, Map.of()));
    }

    private CoordinateTransformResponse coordinateTransform(MineSite site, UUID id) {
        return mapper.findCoordinateTransformById(site.organizationId(), site.id(), id)
                .map(queryService::toResponse)
                .orElseThrow(() -> new MineException(COORDINATE_TRANSFORM_NOT_FOUND, Map.of()));
    }

    private String contentHash(MineCoordinateTransform transform) {
        try {
            Map<String, Object> snapshot = new TreeMap<>();
            snapshot.put("algorithm", transform.algorithm());
            snapshot.put("parameters", canonicalize(
                    objectMapper.readValue(transform.parametersJson(), Object.class)));
            snapshot.put("sourceCoordinateSystemId", transform.sourceCoordinateSystemId().toString());
            snapshot.put("targetCoordinateSystemId", transform.targetCoordinateSystemId().toString());
            snapshot.put("validFrom", transform.validFrom().toString());
            snapshot.put("validTo", transform.validTo() == null ? null : transform.validTo().toString());
            snapshot.put("versionNumber", transform.versionNumber());
            byte[] canonical = objectMapper.writeValueAsString(snapshot)
                    .getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException exception) {
            throw new MineException(ASSET_INVALID, Map.of("transformId", transform.id()));
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

    private String parametersJson(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) throw new MineException(ASSET_INVALID, Map.of());
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (JacksonException exception) {
            throw new MineException(ASSET_INVALID, Map.of());
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

    private Map<String, ?> vehicleSummary(VehicleResponse value) {
        return summary(
                "siteId", value.siteId(), "code", value.vehicleCode(), "name", value.name(),
                "status", value.status(), "version", value.version());
    }

    private Map<String, ?> tagSummary(LocationTagResponse value) {
        return summary(
                "siteId", value.siteId(), "code", value.tagCode(),
                "status", value.status(), "version", value.version());
    }

    private Map<String, ?> coordinateSummary(CoordinateSystemResponse value) {
        return summary(
                "siteId", value.siteId(), "code", value.systemCode(),
                "coordinateType", value.coordinateType(), "unit", value.unit(),
                "unified", value.unified(), "status", value.status(), "version", value.version());
    }

    private Map<String, ?> transformSummary(CoordinateTransformResponse value) {
        return summary(
                "siteId", value.siteId(), "sourceId", value.sourceCoordinateSystemId(),
                "targetId", value.targetCoordinateSystemId(), "versionNumber", value.versionNumber(),
                "algorithm", value.algorithm(), "status", value.status(),
                "contentHash", value.contentHash(), "version", value.version());
    }

    private Map<String, Object> summary(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private MineException vehicleConflict(UUID id) {
        return new MineException(VEHICLE_VERSION_CONFLICT, Map.of("vehicleId", id));
    }

    private MineException tagConflict(UUID id) {
        return new MineException(TAG_VERSION_CONFLICT, Map.of("tagId", id));
    }

    private MineException coordinateConflict(UUID id) {
        return new MineException(COORDINATE_SYSTEM_CONFLICT, Map.of("coordinateSystemId", id));
    }

    private MineException transformConflict(UUID id) {
        return new MineException(COORDINATE_TRANSFORM_CONFLICT, Map.of("transformId", id));
    }

    private MineException invalid(Exception cause) {
        return new MineException(ASSET_INVALID, Map.of("exceptionType", cause.getClass().getSimpleName()));
    }

    private String normalizeCode(String value) {
        return required(value).toLowerCase(Locale.ROOT);
    }

    private String upper(String value) {
        return required(value).toUpperCase(Locale.ROOT);
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new MineException(ASSET_INVALID, Map.of());
        return value.strip();
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private double requiredNumber(Double value) {
        if (value == null || !Double.isFinite(value)) throw new MineException(ASSET_INVALID, Map.of());
        return value;
    }
}
