package com.bosco.mine.server.modules.mine.scene;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneVersionResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 提供按矿区隔离的稳定场景、版本和有序图层查询。
 */
@Service
public class SceneQueryService {

    private static final ErrorCode INVALID =
            new ErrorCode("MINE-SCENE-422-001", "场景查询参数不合法");
    private static final ErrorCode SCENE_NOT_FOUND =
            new ErrorCode("MINE-SCENE-404-001", "场景不存在");
    private static final ErrorCode VERSION_NOT_FOUND =
            new ErrorCode("MINE-SCENE-404-002", "场景版本不存在");
    private static final ErrorCode PUBLISHED_NOT_FOUND =
            new ErrorCode("MINE-SCENE-404-003", "场景尚无可用发布版本");
    private static final Set<String> SCENE_STATUSES = Set.of("ACTIVE", "ARCHIVED");

    private final SceneMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;

    /**
     * 创建场景查询服务。
     *
     * @param mapper 场景数据访问接口
     * @param authorizer 矿区作用域授权器
     * @param objectMapper JSON 映射器
     */
    public SceneQueryService(
            SceneMapper mapper,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页查询稳定场景。
     *
     * @param siteId 矿区标识
     * @param keyword 可选编码或名称关键字
     * @param status 可选生命周期状态
     * @param page 页码
     * @param size 页大小，最大一百
     * @return 稳定场景分页
     */
    @Transactional(readOnly = true)
    public PageResponse<SceneResponse> findScenes(
            UUID siteId,
            String keyword,
            String status,
            int page,
            int size
    ) {
        authorizer.requireSite(siteId);
        validatePage(page, size);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedStatus = normalizeStatus(status);
        long offset = offset(page, size);
        return new PageResponse<>(
                mapper.findScenes(siteId, normalizedKeyword, normalizedStatus, offset, size)
                        .stream().map(this::sceneResponse).toList(),
                page, size,
                mapper.countScenes(siteId, normalizedKeyword, normalizedStatus));
    }

    /** 查询一个稳定场景。 */
    @Transactional(readOnly = true)
    public SceneResponse findScene(UUID siteId, UUID sceneId) {
        authorizer.requireSite(siteId);
        return sceneResponse(requiredScene(siteId, sceneId));
    }

    /** 按修订号倒序查询场景全部版本及其图层。 */
    @Transactional(readOnly = true)
    public List<SceneVersionResponse> findVersions(UUID siteId, UUID sceneId) {
        authorizer.requireSite(siteId);
        requiredScene(siteId, sceneId);
        return mapper.findSceneVersions(siteId, sceneId).stream()
                .map(this::versionResponse)
                .toList();
    }

    /** 查询一个场景版本及其有序图层。 */
    @Transactional(readOnly = true)
    public SceneVersionResponse findVersion(UUID siteId, UUID sceneId, UUID versionId) {
        authorizer.requireSite(siteId);
        requiredScene(siteId, sceneId);
        return versionResponse(requiredVersion(siteId, sceneId, versionId));
    }

    /** 查询场景最后正确的当前发布版本。 */
    @Transactional(readOnly = true)
    public SceneVersionResponse findCurrentPublished(UUID siteId, UUID sceneId) {
        authorizer.requireSite(siteId);
        requiredScene(siteId, sceneId);
        return versionResponse(mapper.findCurrentPublishedVersion(siteId, sceneId)
                .orElseThrow(() -> new MineException(PUBLISHED_NOT_FOUND, Map.of())));
    }

    SceneMapper.SceneRecord requiredScene(UUID siteId, UUID sceneId) {
        return mapper.findSceneById(siteId, sceneId)
                .orElseThrow(() -> new MineException(SCENE_NOT_FOUND, Map.of()));
    }

    SceneMapper.SceneVersionRecord requiredVersion(
            UUID siteId,
            UUID sceneId,
            UUID versionId
    ) {
        return mapper.findSceneVersion(siteId, sceneId, versionId)
                .orElseThrow(() -> new MineException(VERSION_NOT_FOUND, Map.of()));
    }

    SceneResponse sceneResponse(SceneMapper.SceneRecord record) {
        return new SceneResponse(
                record.id(), record.organizationId(), record.siteId(), record.sceneCode(),
                record.name(), record.description(), record.defaultScene(), record.status(),
                record.createdAt(), record.updatedAt(), record.archivedAt(), record.version());
    }

    SceneVersionResponse versionResponse(SceneMapper.SceneVersionRecord record) {
        List<SceneVersionResponse.SceneLayerResponse> layers = mapper.findSceneLayers(
                        record.siteId(), record.id()).stream()
                .map(this::layerResponse)
                .toList();
        return new SceneVersionResponse(
                record.id(), record.sceneId(), record.coordinateSystemId(),
                record.revisionNumber(), record.schemaVersion(), record.coordinateMode(),
                record.originX(), record.originY(), record.originZ(),
                record.headingDegrees(), record.pitchDegrees(), record.rollDegrees(),
                record.scale(), readJson(record.defaultCameraJson()),
                readJson(record.fallbackViewportJson()), record.status(), record.contentHash(),
                record.publishedAt(), record.supersededAt(), record.createdAt(),
                record.version(), layers);
    }

    private SceneVersionResponse.SceneLayerResponse layerResponse(
            SceneMapper.SceneLayerRecord record
    ) {
        return new SceneVersionResponse.SceneLayerResponse(
                record.id(), record.layerCode(), record.orderNumber(), record.layerType(),
                record.assetId(), record.visible(), record.opacity(),
                record.minimumDistance(), record.maximumDistance(),
                record.offsetX(), record.offsetY(), record.offsetZ(),
                record.headingDegrees(), record.pitchDegrees(), record.rollDegrees(),
                record.scale(), record.maxItems(), readJson(record.optionsJson()));
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库场景 JSON 快照无法解析", exception);
        }
    }

    private String normalizeKeyword(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 128) {
            throw invalid();
        }
        return "%" + normalized.replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!SCENE_STATUSES.contains(normalized)) {
            throw invalid();
        }
        return normalized;
    }

    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw invalid();
        }
    }

    private long offset(int page, int size) {
        try {
            return Math.multiplyExact((long) page - 1L, size);
        } catch (ArithmeticException exception) {
            throw invalid();
        }
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }
}
