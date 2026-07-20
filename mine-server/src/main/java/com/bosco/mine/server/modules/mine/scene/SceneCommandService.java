package com.bosco.mine.server.modules.mine.scene;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;
import com.bosco.mine.server.modules.mine.scene.domain.SceneVersionPolicy;
import com.bosco.mine.server.modules.mine.scene.dto.SceneLayerRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneVersionRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneVersionResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理稳定场景、草稿、图层整体替换和不可变发布事务。
 */
@Service
public class SceneCommandService {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Set<SceneVersionPolicy.LayerType> DYNAMIC_TYPES = Set.of(
            SceneVersionPolicy.LayerType.DYNAMIC_LOCATIONS,
            SceneVersionPolicy.LayerType.DYNAMIC_REGIONS,
            SceneVersionPolicy.LayerType.DYNAMIC_TRAJECTORY,
            SceneVersionPolicy.LayerType.DYNAMIC_ALARMS,
            SceneVersionPolicy.LayerType.DYNAMIC_OPERATIONS);
    private static final ErrorCode INVALID =
            new ErrorCode("MINE-SCENE-422-001", "场景请求不合法");
    private static final ErrorCode DUPLICATE_CODE =
            new ErrorCode("MINE-SCENE-409-001", "矿区内场景编码已存在");
    private static final ErrorCode SCENE_CONFLICT =
            new ErrorCode("MINE-SCENE-409-002", "场景已被并发修改");
    private static final ErrorCode DEFAULT_ARCHIVE_CONFLICT =
            new ErrorCode("MINE-SCENE-409-003", "默认场景必须先切换后才能归档");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("MINE-SCENE-409-004", "场景版本已发布或已被并发修改");
    private static final ErrorCode INVALID_COORDINATE =
            new ErrorCode("MINE-SCENE-422-003", "场景必须引用同矿区活动统一坐标系");
    private static final ErrorCode INVALID_LAYER_ASSET =
            new ErrorCode("MINE-SCENE-422-004", "场景图层资源不存在、不可用或类型不匹配");

    private final SceneMapper mapper;
    private final SceneQueryService queryService;
    private final SceneActionIdempotencyService idempotencyService;
    private final MineScopeAuthorizer authorizer;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建场景命令服务。
     *
     * @param mapper 场景数据访问接口
     * @param queryService 场景查询服务
     * @param idempotencyService 场景动作幂等服务
     * @param authorizer 矿区作用域授权器
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public SceneCommandService(
            SceneMapper mapper,
            SceneQueryService queryService,
            SceneActionIdempotencyService idempotencyService,
            MineScopeAuthorizer authorizer,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.queryService = queryService;
        this.idempotencyService = idempotencyService;
        this.authorizer = authorizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 创建一个矿区内编码稳定的场景。 */
    @Transactional
    public SceneResponse createScene(UUID siteId, SceneRequest request) {
        MineSite site = authorizer.requireSite(siteId);
        NormalizedScene normalized = normalizeScene(request);
        UUID operatorId = authorizer.currentUserId();
        UUID sceneId = idGenerator.next();
        if (mapper.insertScene(
                sceneId, site.organizationId(), siteId, normalized.code(),
                normalized.name(), normalized.description(), operatorId) != 1) {
            throw new MineException(DUPLICATE_CODE, Map.of("sceneCode", normalized.code()));
        }
        SceneResponse response = queryService.sceneResponse(requiredScene(siteId, sceneId));
        auditService.recordSuccess(
                "MINE_SCENE", "CREATE_SCENE", "mine:scene:edit", "SCENE", sceneId,
                Map.of(), Map.of("siteId", siteId, "sceneCode", response.sceneCode()));
        return response;
    }

    /** 使用乐观锁修改稳定场景名称和说明，稳定编码不可修改。 */
    @Transactional
    public SceneResponse updateScene(UUID siteId, UUID sceneId, SceneRequest request) {
        authorizer.requireSite(siteId);
        NormalizedScene normalized = normalizeScene(request);
        SceneMapper.SceneRecord before = requiredScene(siteId, sceneId);
        if (!before.sceneCode().equals(normalized.code())) {
            throw invalid();
        }
        if (mapper.updateScene(
                siteId, sceneId, normalized.name(), normalized.description(),
                request.version(), authorizer.currentUserId()) != 1) {
            throw new MineException(SCENE_CONFLICT, Map.of("sceneId", sceneId));
        }
        SceneResponse response = queryService.sceneResponse(requiredScene(siteId, sceneId));
        auditService.recordSuccess(
                "MINE_SCENE", "UPDATE_SCENE", "mine:scene:edit", "SCENE", sceneId,
                Map.of("name", before.name(), "version", before.version()),
                Map.of("name", response.name(), "version", response.version()));
        return response;
    }

    /** 使用持久幂等键和乐观版本切换矿区默认场景。 */
    public SceneResponse setDefault(
            UUID siteId,
            UUID sceneId,
            long version,
            String idempotencyKey
    ) {
        return idempotencyService.execute(
                siteId, idempotencyKey, "SET_DEFAULT_SCENE", "SCENE", sceneId,
                Map.of("sceneId", sceneId, "version", version),
                SceneResponse.class,
                () -> setDefaultOnce(siteId, sceneId, version));
    }

    /** 使用持久幂等键和乐观版本归档非默认场景。 */
    public SceneResponse archiveScene(
            UUID siteId,
            UUID sceneId,
            long version,
            String idempotencyKey
    ) {
        return idempotencyService.execute(
                siteId, idempotencyKey, "ARCHIVE_SCENE", "SCENE", sceneId,
                Map.of("sceneId", sceneId, "version", version),
                SceneResponse.class,
                () -> archiveSceneOnce(siteId, sceneId, version));
    }

    /** 创建一个首版场景草稿。 */
    @Transactional
    public SceneVersionResponse createDraft(
            UUID siteId,
            UUID sceneId,
            SceneVersionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        NormalizedVersion normalized = normalizeVersion(siteId, request);
        requireLockedActiveScene(siteId, sceneId);
        if (mapper.findDraftVersion(siteId, sceneId).isPresent()) {
            throw new MineException(VERSION_CONFLICT, Map.of("sceneId", sceneId));
        }
        UUID versionId = idGenerator.next();
        if (!insertVersion(
                versionId, site, sceneId, mapper.maxRevisionNumber(siteId, sceneId) + 1,
                normalized, authorizer.currentUserId())) {
            throw new MineException(VERSION_CONFLICT, Map.of("sceneId", sceneId));
        }
        SceneVersionResponse response = queryService.versionResponse(
                requiredVersion(siteId, sceneId, versionId));
        auditService.recordSuccess(
                "MINE_SCENE", "CREATE_SCENE_DRAFT", "mine:scene:edit",
                "SCENE_VERSION", versionId, Map.of(),
                Map.of("sceneId", sceneId, "revisionNumber", response.revisionNumber()));
        return response;
    }

    /** 从当前发布版本完整复制坐标、视图和有序图层生成下一草稿。 */
    @Transactional
    public SceneVersionResponse createNextDraft(UUID siteId, UUID sceneId) {
        MineSite site = authorizer.requireSite(siteId);
        requireLockedActiveScene(siteId, sceneId);
        if (mapper.findDraftVersion(siteId, sceneId).isPresent()) {
            throw new MineException(VERSION_CONFLICT, Map.of("sceneId", sceneId));
        }
        SceneMapper.SceneVersionRecord published = mapper.findCurrentPublishedVersion(
                        siteId, sceneId)
                .orElseThrow(() -> new MineException(VERSION_CONFLICT, Map.of("sceneId", sceneId)));
        validateCoordinate(siteId, published.coordinateSystemId());
        List<SceneMapper.SceneLayerRecord> sourceLayers = mapper.findSceneLayers(
                siteId, published.id());
        validateLayerRecords(sourceLayers);

        UUID versionId = idGenerator.next();
        if (mapper.insertSceneVersion(
                versionId, site.organizationId(), siteId, sceneId,
                published.coordinateSystemId(), mapper.maxRevisionNumber(siteId, sceneId) + 1,
                published.coordinateMode(), published.originX(), published.originY(),
                published.originZ(), published.headingDegrees(), published.pitchDegrees(),
                published.rollDegrees(), published.scale(), published.defaultCameraJson(),
                published.fallbackViewportJson(), authorizer.currentUserId()) != 1) {
            throw new MineException(VERSION_CONFLICT, Map.of("sceneId", sceneId));
        }
        for (SceneMapper.SceneLayerRecord layer : sourceLayers) {
            insertLayer(site, versionId, layer);
        }
        SceneVersionResponse response = queryService.versionResponse(
                requiredVersion(siteId, sceneId, versionId));
        auditService.recordSuccess(
                "MINE_SCENE", "CLONE_SCENE_DRAFT", "mine:scene:edit",
                "SCENE_VERSION", versionId, Map.of("sourceVersionId", published.id()),
                Map.of("sceneId", sceneId, "revisionNumber", response.revisionNumber()));
        return response;
    }

    /** 使用乐观锁修改草稿坐标和视图配置。 */
    @Transactional
    public SceneVersionResponse updateDraft(
            UUID siteId,
            UUID sceneId,
            UUID versionId,
            SceneVersionRequest request
    ) {
        authorizer.requireSite(siteId);
        requireActiveScene(siteId, sceneId);
        SceneMapper.SceneVersionRecord before = requiredVersion(siteId, sceneId, versionId);
        if (!"DRAFT".equals(before.status())) {
            throw new MineException(VERSION_CONFLICT, Map.of("versionId", versionId));
        }
        NormalizedVersion normalized = normalizeVersion(siteId, request);
        if (mapper.updateSceneVersion(
                siteId, sceneId, versionId, normalized.coordinateSystemId(),
                normalized.coordinateMode(), normalized.originX(), normalized.originY(),
                normalized.originZ(), normalized.headingDegrees(), normalized.pitchDegrees(),
                normalized.rollDegrees(), normalized.scale(), normalized.defaultCameraJson(),
                normalized.fallbackViewportJson(), request.version()) != 1) {
            throw new MineException(VERSION_CONFLICT, Map.of("versionId", versionId));
        }
        return queryService.versionResponse(requiredVersion(siteId, sceneId, versionId));
    }

    /** 使用乐观锁整体替换草稿图层，禁止部分增量造成半包。 */
    @Transactional
    public SceneVersionResponse replaceLayers(
            UUID siteId,
            UUID sceneId,
            UUID versionId,
            long version,
            List<SceneLayerRequest> layers
    ) {
        MineSite site = authorizer.requireSite(siteId);
        requireActiveScene(siteId, sceneId);
        SceneMapper.SceneVersionRecord draft = requiredVersion(siteId, sceneId, versionId);
        if (!"DRAFT".equals(draft.status())) {
            throw new MineException(VERSION_CONFLICT, Map.of("versionId", versionId));
        }
        List<NormalizedLayer> normalized = normalizeLayers(siteId, layers);
        int previousLayerCount = mapper.findSceneLayers(siteId, versionId).size();
        if (mapper.touchDraftVersion(siteId, sceneId, versionId, version) != 1) {
            throw new MineException(VERSION_CONFLICT, Map.of("versionId", versionId));
        }
        mapper.deleteDraftLayers(siteId, versionId);
        for (NormalizedLayer layer : normalized) {
            insertLayer(site, versionId, layer.request());
        }
        SceneVersionResponse response = queryService.versionResponse(
                requiredVersion(siteId, sceneId, versionId));
        auditService.recordSuccess(
                "MINE_SCENE", "REPLACE_SCENE_LAYERS", "mine:scene:edit",
                "SCENE_VERSION", versionId,
                Map.of("previousLayerCount", previousLayerCount),
                Map.of("layerCount", response.layers().size(), "version", response.version()));
        return response;
    }

    /** 使用持久幂等键和乐观版本发布一个不可变场景版本。 */
    public SceneVersionResponse publish(
            UUID siteId,
            UUID sceneId,
            UUID versionId,
            long version,
            String idempotencyKey
    ) {
        return idempotencyService.execute(
                siteId, idempotencyKey, "PUBLISH_SCENE", "SCENE_VERSION", versionId,
                Map.of("sceneId", sceneId, "versionId", versionId, "version", version),
                SceneVersionResponse.class,
                () -> publishOnce(siteId, sceneId, versionId, version));
    }

    private SceneResponse setDefaultOnce(UUID siteId, UUID sceneId, long version) {
        mapper.lockSceneSite(siteId);
        SceneMapper.SceneRecord scene = mapper.lockScene(siteId, sceneId)
                .orElseThrow(() -> new MineException(
                        new ErrorCode("MINE-SCENE-404-001", "场景不存在"), Map.of()));
        if (!"ACTIVE".equals(scene.status()) || scene.version() != version) {
            throw new MineException(SCENE_CONFLICT, Map.of("sceneId", sceneId));
        }
        UUID operatorId = authorizer.currentUserId();
        mapper.clearOtherDefaultScenes(siteId, sceneId, operatorId);
        if (mapper.setDefaultScene(siteId, sceneId, version, operatorId) != 1) {
            throw new MineException(SCENE_CONFLICT, Map.of("sceneId", sceneId));
        }
        SceneResponse response = queryService.sceneResponse(requiredScene(siteId, sceneId));
        auditService.recordSuccess(
                "MINE_SCENE", "SET_DEFAULT_SCENE", "mine:scene:publish", "SCENE", sceneId,
                Map.of("defaultScene", scene.defaultScene(), "version", scene.version()),
                Map.of("defaultScene", true, "version", response.version()));
        return response;
    }

    private SceneResponse archiveSceneOnce(UUID siteId, UUID sceneId, long version) {
        SceneMapper.SceneRecord scene = mapper.lockScene(siteId, sceneId)
                .orElseThrow(() -> new MineException(
                        new ErrorCode("MINE-SCENE-404-001", "场景不存在"), Map.of()));
        if (scene.defaultScene()) {
            throw new MineException(DEFAULT_ARCHIVE_CONFLICT, Map.of("sceneId", sceneId));
        }
        if (mapper.archiveScene(siteId, sceneId, version, authorizer.currentUserId()) != 1) {
            throw new MineException(SCENE_CONFLICT, Map.of("sceneId", sceneId));
        }
        SceneResponse response = queryService.sceneResponse(requiredScene(siteId, sceneId));
        auditService.recordSuccess(
                "MINE_SCENE", "ARCHIVE_SCENE", "mine:scene:edit", "SCENE", sceneId,
                Map.of("status", scene.status(), "version", scene.version()),
                Map.of("status", response.status(), "version", response.version()));
        return response;
    }

    private SceneVersionResponse publishOnce(
            UUID siteId,
            UUID sceneId,
            UUID versionId,
            long version
    ) {
        SceneMapper.SceneRecord scene = mapper.lockScene(siteId, sceneId)
                .orElseThrow(() -> new MineException(
                        new ErrorCode("MINE-SCENE-404-001", "场景不存在"), Map.of()));
        if (!"ACTIVE".equals(scene.status())) {
            throw new MineException(VERSION_CONFLICT, Map.of("sceneId", sceneId));
        }
        SceneMapper.SceneVersionRecord draft = mapper.lockSceneVersion(siteId, sceneId, versionId)
                .orElseThrow(() -> new MineException(
                        new ErrorCode("MINE-SCENE-404-002", "场景版本不存在"), Map.of()));
        if (!"DRAFT".equals(draft.status()) || draft.version() != version) {
            throw new MineException(VERSION_CONFLICT, Map.of("versionId", versionId));
        }
        validateCoordinate(siteId, draft.coordinateSystemId());
        List<SceneMapper.SceneLayerRecord> rows = mapper.findSceneLayers(siteId, versionId);
        if (rows.isEmpty()) {
            throw invalid();
        }
        List<SceneVersionPolicy.LayerDefinition> layers = validateLayerRecords(rows);
        SceneVersionPolicy.SceneDefinition definition = new SceneVersionPolicy.SceneDefinition(
                draft.coordinateSystemId(), coordinateMode(draft.coordinateMode()),
                new SceneVersionPolicy.Vector3(draft.originX(), draft.originY(), draft.originZ()),
                new SceneVersionPolicy.Rotation(
                        draft.headingDegrees(), draft.pitchDegrees(), draft.rollDegrees()),
                draft.scale());
        String contentHash;
        try {
            contentHash = SceneVersionPolicy.contentHash(definition, layers);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        mapper.supersedeCurrentPublished(siteId, sceneId, versionId);
        if (mapper.publishDraftVersion(
                siteId, sceneId, versionId, version, contentHash,
                authorizer.currentUserId()) != 1) {
            throw new MineException(VERSION_CONFLICT, Map.of("versionId", versionId));
        }
        SceneVersionResponse response = queryService.versionResponse(
                requiredVersion(siteId, sceneId, versionId));
        auditService.recordSuccess(
                "MINE_SCENE", "PUBLISH_SCENE", "mine:scene:publish",
                "SCENE_VERSION", versionId,
                Map.of("status", draft.status(), "version", draft.version()),
                Map.of("status", response.status(), "contentHash", response.contentHash(),
                        "revisionNumber", response.revisionNumber()));
        return response;
    }

    private List<NormalizedLayer> normalizeLayers(
            UUID siteId,
            List<SceneLayerRequest> requests
    ) {
        if (requests == null) {
            throw invalid();
        }
        Map<String, SceneLayerRequest> byCode = new HashMap<>();
        List<SceneVersionPolicy.LayerDefinition> definitions = new ArrayList<>();
        for (SceneLayerRequest request : requests) {
            if (request == null) {
                throw invalid();
            }
            SceneVersionPolicy.LayerType type = layerType(request.layerType());
            SceneAssetPolicy.SceneAssetType assetType = null;
            if (request.assetId() != null) {
                SceneMapper.SceneAssetRecord asset = mapper.findAssetById(siteId, request.assetId())
                        .orElseThrow(() -> new MineException(INVALID_LAYER_ASSET, Map.of()));
                if (!"AVAILABLE".equals(asset.status())) {
                    throw new MineException(INVALID_LAYER_ASSET, Map.of());
                }
                assetType = SceneAssetPolicy.SceneAssetType.valueOf(asset.assetType());
            }
            if ((type == SceneVersionPolicy.LayerType.STATIC_GLB
                    && assetType != SceneAssetPolicy.SceneAssetType.GLB)
                    || (type == SceneVersionPolicy.LayerType.STATIC_3D_TILES
                    && assetType != SceneAssetPolicy.SceneAssetType.TILES_3D)
                    || (type != SceneVersionPolicy.LayerType.STATIC_GLB
                    && type != SceneVersionPolicy.LayerType.STATIC_3D_TILES
                    && request.assetId() != null)) {
                throw new MineException(INVALID_LAYER_ASSET, Map.of());
            }
            validateOptions(request.options());
            if ((DYNAMIC_TYPES.contains(type) && request.maxItems() < 1)
                    || (!DYNAMIC_TYPES.contains(type) && request.maxItems() != 0)) {
                throw invalid();
            }
            SceneVersionPolicy.LayerDefinition definition = layerDefinition(
                    request, type, assetType);
            definitions.add(definition);
            byCode.put(request.layerCode(), request);
        }
        List<SceneVersionPolicy.LayerDefinition> normalized;
        try {
            normalized = SceneVersionPolicy.normalizeLayers(definitions);
        } catch (IllegalArgumentException exception) {
            if (definitions.stream().anyMatch(item ->
                    item.assetId() != null && item.assetType() == null)) {
                throw new MineException(INVALID_LAYER_ASSET, Map.of());
            }
            throw invalid();
        }
        return normalized.stream()
                .map(definition -> new NormalizedLayer(byCode.get(definition.code()), definition))
                .toList();
    }

    private List<SceneVersionPolicy.LayerDefinition> validateLayerRecords(
            List<SceneMapper.SceneLayerRecord> rows
    ) {
        List<SceneVersionPolicy.LayerDefinition> definitions = new ArrayList<>(rows.size());
        for (SceneMapper.SceneLayerRecord row : rows) {
            if (row.assetId() != null && (!"AVAILABLE".equals(row.assetStatus())
                    || row.assetType() == null)) {
                throw new MineException(INVALID_LAYER_ASSET, Map.of());
            }
            SceneAssetPolicy.SceneAssetType assetType = row.assetType() == null
                    ? null : SceneAssetPolicy.SceneAssetType.valueOf(row.assetType());
            definitions.add(new SceneVersionPolicy.LayerDefinition(
                    row.layerCode(), row.orderNumber(), layerType(row.layerType()),
                    row.assetId(), assetType, row.visible(), row.opacity(),
                    row.minimumDistance(), row.maximumDistance(),
                    new SceneVersionPolicy.Vector3(row.offsetX(), row.offsetY(), row.offsetZ()),
                    new SceneVersionPolicy.Rotation(
                            row.headingDegrees(), row.pitchDegrees(), row.rollDegrees()),
                    row.scale(), row.maxItems()));
        }
        try {
            return SceneVersionPolicy.normalizeLayers(definitions);
        } catch (IllegalArgumentException exception) {
            throw new MineException(INVALID_LAYER_ASSET, Map.of());
        }
    }

    private SceneVersionPolicy.LayerDefinition layerDefinition(
            SceneLayerRequest request,
            SceneVersionPolicy.LayerType type,
            SceneAssetPolicy.SceneAssetType assetType
    ) {
        return new SceneVersionPolicy.LayerDefinition(
                request.layerCode(), request.orderNumber(), type,
                request.assetId(), assetType, request.visible(), request.opacity(),
                request.minimumDistance(), request.maximumDistance(),
                new SceneVersionPolicy.Vector3(
                        request.offsetX(), request.offsetY(), request.offsetZ()),
                new SceneVersionPolicy.Rotation(
                        request.headingDegrees(), request.pitchDegrees(),
                        request.rollDegrees()),
                request.scale(), request.maxItems());
    }

    private boolean insertVersion(
            UUID versionId,
            MineSite site,
            UUID sceneId,
            int revisionNumber,
            NormalizedVersion version,
            UUID operatorId
    ) {
        return mapper.insertSceneVersion(
                versionId, site.organizationId(), site.id(), sceneId,
                version.coordinateSystemId(), revisionNumber, version.coordinateMode(),
                version.originX(), version.originY(), version.originZ(),
                version.headingDegrees(), version.pitchDegrees(), version.rollDegrees(),
                version.scale(), version.defaultCameraJson(),
                version.fallbackViewportJson(), operatorId) == 1;
    }

    private void insertLayer(MineSite site, UUID versionId, SceneLayerRequest layer) {
        mapper.insertSceneLayer(
                idGenerator.next(), site.organizationId(), site.id(), versionId,
                layer.layerCode(), layer.orderNumber(), layer.layerType(), layer.assetId(),
                layer.visible(), layer.opacity(), layer.minimumDistance(),
                layer.maximumDistance(), layer.offsetX(), layer.offsetY(), layer.offsetZ(),
                layer.headingDegrees(), layer.pitchDegrees(), layer.rollDegrees(),
                layer.scale(), layer.maxItems(), writeJson(layer.options()));
    }

    private void insertLayer(MineSite site, UUID versionId, SceneMapper.SceneLayerRecord layer) {
        mapper.insertSceneLayer(
                idGenerator.next(), site.organizationId(), site.id(), versionId,
                layer.layerCode(), layer.orderNumber(), layer.layerType(), layer.assetId(),
                layer.visible(), layer.opacity(), layer.minimumDistance(),
                layer.maximumDistance(), layer.offsetX(), layer.offsetY(), layer.offsetZ(),
                layer.headingDegrees(), layer.pitchDegrees(), layer.rollDegrees(),
                layer.scale(), layer.maxItems(), layer.optionsJson());
    }

    private NormalizedVersion normalizeVersion(UUID siteId, SceneVersionRequest request) {
        if (request == null || request.coordinateSystemId() == null
                || request.version() < 0) {
            throw invalid();
        }
        validateCoordinate(siteId, request.coordinateSystemId());
        SceneVersionPolicy.CoordinateMode mode = coordinateMode(request.coordinateMode());
        try {
            SceneVersionPolicy.validateTransform(
                    new SceneVersionPolicy.Vector3(
                            request.originX(), request.originY(), request.originZ()),
                    new SceneVersionPolicy.Rotation(
                            request.headingDegrees(), request.pitchDegrees(),
                            request.rollDegrees()),
                    request.scale());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        validateViewJson(request.defaultCamera());
        validateViewJson(request.fallbackViewport());
        return new NormalizedVersion(
                request.coordinateSystemId(), mode.name(),
                request.originX(), request.originY(), request.originZ(),
                request.headingDegrees(), request.pitchDegrees(), request.rollDegrees(),
                request.scale(), writeJson(request.defaultCamera()),
                writeJson(request.fallbackViewport()));
    }

    private void validateCoordinate(UUID siteId, UUID coordinateSystemId) {
        SceneMapper.SceneCoordinateRecord coordinate = mapper.findSceneCoordinate(
                        siteId, coordinateSystemId)
                .orElseThrow(() -> new MineException(INVALID_COORDINATE, Map.of()));
        if (!coordinate.unified() || !"ACTIVE".equals(coordinate.status())) {
            throw new MineException(INVALID_COORDINATE, Map.of());
        }
    }

    private void validateViewJson(JsonNode value) {
        if (value == null || !value.isObject() || writeJson(value).length() > 16_384) {
            throw invalid();
        }
    }

    private void validateOptions(JsonNode value) {
        if (value == null || !value.isObject() || writeJson(value).length() > 16_384) {
            throw invalid();
        }
    }

    private NormalizedScene normalizeScene(SceneRequest request) {
        if (request == null || request.sceneCode() == null
                || !CODE.matcher(request.sceneCode()).matches()
                || request.name() == null || request.name().isBlank()
                || request.name().strip().length() > 128
                || request.version() < 0) {
            throw invalid();
        }
        String description = request.description();
        if (description != null) {
            description = description.strip();
            if (description.isEmpty() || description.length() > 500) {
                throw invalid();
            }
        }
        return new NormalizedScene(request.sceneCode(), request.name().strip(), description);
    }

    private SceneVersionPolicy.CoordinateMode coordinateMode(String value) {
        try {
            return SceneVersionPolicy.CoordinateMode.valueOf(
                    Objects.requireNonNull(value, "坐标模式不能为空")
                            .strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private SceneVersionPolicy.LayerType layerType(String value) {
        try {
            return SceneVersionPolicy.LayerType.valueOf(
                    Objects.requireNonNull(value, "图层类型不能为空")
                            .strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            MineException failure = invalid();
            failure.addSuppressed(exception);
            throw failure;
        }
    }

    private SceneMapper.SceneRecord requiredScene(UUID siteId, UUID sceneId) {
        return queryService.requiredScene(siteId, sceneId);
    }

    private SceneMapper.SceneRecord requireActiveScene(UUID siteId, UUID sceneId) {
        SceneMapper.SceneRecord scene = requiredScene(siteId, sceneId);
        if (!"ACTIVE".equals(scene.status())) {
            throw new MineException(SCENE_CONFLICT, Map.of("sceneId", sceneId));
        }
        return scene;
    }

    private SceneMapper.SceneRecord requireLockedActiveScene(UUID siteId, UUID sceneId) {
        SceneMapper.SceneRecord scene = mapper.lockScene(siteId, sceneId)
                .orElseThrow(() -> new MineException(
                        new ErrorCode("MINE-SCENE-404-001", "场景不存在"), Map.of()));
        if (!"ACTIVE".equals(scene.status())) {
            throw new MineException(SCENE_CONFLICT, Map.of("sceneId", sceneId));
        }
        return scene;
    }

    private SceneMapper.SceneVersionRecord requiredVersion(
            UUID siteId,
            UUID sceneId,
            UUID versionId
    ) {
        return queryService.requiredVersion(siteId, sceneId, versionId);
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }

    private record NormalizedScene(String code, String name, String description) {
    }

    private record NormalizedVersion(
            UUID coordinateSystemId,
            String coordinateMode,
            double originX,
            double originY,
            double originZ,
            double headingDegrees,
            double pitchDegrees,
            double rollDegrees,
            double scale,
            String defaultCameraJson,
            String fallbackViewportJson
    ) {
    }

    private record NormalizedLayer(
            SceneLayerRequest request,
            SceneVersionPolicy.LayerDefinition definition
    ) {
    }
}
