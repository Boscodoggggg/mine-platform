package com.bosco.mine.server.modules.mine.scene;

import com.bosco.mine.server.modules.mine.scene.domain.SceneAssetPolicy;
import com.bosco.mine.server.modules.mine.scene.domain.SceneVersionPolicy;
import com.bosco.mine.server.modules.mine.scene.dto.SceneRuntimeResponse;
import com.bosco.mine.server.modules.mine.scene.storage.SceneAssetStorage;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 读取并复核默认场景最后正确发布版本，任何半包只返回结构化降级结果。
 */
@Service
public class SceneRuntimeService {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_LAYERS = 256;
    private static final Set<String> READABLE_ASSET_STATUSES = Set.of("AVAILABLE", "ARCHIVED");

    private final SceneMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final SceneAssetStorage storage;
    private final ObjectMapper objectMapper;

    /**
     * 创建场景运行快照服务。
     *
     * @param mapper 场景数据访问接口
     * @param authorizer 矿区作用域授权器
     * @param storage 三维资源存储端口
     * @param objectMapper JSON 映射器
     */
    public SceneRuntimeService(
            SceneMapper mapper,
            MineScopeAuthorizer authorizer,
            SceneAssetStorage storage,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载受权矿区默认场景的完整运行快照。
     *
     * @param siteId 矿区标识
     * @return 完整快照或不含半包的固定降级结果
     */
    @Transactional(readOnly = true)
    public SceneRuntimeResponse load(UUID siteId) {
        authorizer.requireSite(siteId);
        List<SceneMapper.SceneRuntimeRow> rows = mapper.findRuntimeRows(siteId);
        if (rows.isEmpty() || rows.getFirst().sceneId() == null) {
            return degraded(siteId, "NO_DEFAULT_SCENE");
        }
        SceneMapper.SceneRuntimeRow first = rows.getFirst();
        if (first.versionId() == null) {
            return degraded(siteId, "DEFAULT_SCENE_UNPUBLISHED");
        }
        if (first.schemaVersion() == null
                || first.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            return degraded(siteId, "UNSUPPORTED_SCHEMA_VERSION");
        }
        try {
            return new SceneRuntimeResponse(
                    siteId, "READY", "NONE", buildSnapshot(siteId, rows));
        } catch (StorageUnavailableException exception) {
            return degraded(siteId, "SCENE_STORAGE_UNAVAILABLE");
        } catch (RuntimeException exception) {
            return degraded(siteId, "INVALID_PUBLISHED_SNAPSHOT");
        }
    }

    private SceneRuntimeResponse.RuntimeSnapshot buildSnapshot(
            UUID siteId,
            List<SceneMapper.SceneRuntimeRow> rows
    ) {
        SceneMapper.SceneRuntimeRow first = rows.getFirst();
        requireCompleteHeader(first);
        if (first.layerCount() < 1 || first.layerCount() > MAX_LAYERS
                || rows.size() != first.layerCount()) {
            throw new IllegalArgumentException("场景运行图层数量不完整");
        }

        SceneVersionPolicy.CoordinateMode coordinateMode =
                SceneVersionPolicy.CoordinateMode.valueOf(first.coordinateMode());
        SceneVersionPolicy.SceneDefinition sceneDefinition =
                new SceneVersionPolicy.SceneDefinition(
                        first.coordinateSystemId(), coordinateMode,
                        new SceneVersionPolicy.Vector3(
                                first.originX(), first.originY(), first.originZ()),
                        new SceneVersionPolicy.Rotation(
                                first.headingDegrees(), first.pitchDegrees(),
                                first.rollDegrees()),
                        first.sceneScale());
        SceneVersionPolicy.validateTransform(
                sceneDefinition.origin(), sceneDefinition.rotation(), sceneDefinition.scale());

        List<SceneRuntimeResponse.RuntimeLayer> layers = new ArrayList<>(rows.size());
        List<SceneVersionPolicy.LayerDefinition> definitions = new ArrayList<>(rows.size());
        Map<String, String> layerOptions = new HashMap<>();
        for (SceneMapper.SceneRuntimeRow row : rows) {
            validateSameSnapshot(first, row);
            LayerBuildResult layer = buildLayer(siteId, row);
            layers.add(layer.response());
            definitions.add(layer.definition());
            layerOptions.put(row.layerCode(), row.optionsJson());
        }
        List<SceneVersionPolicy.LayerDefinition> normalized =
                SceneVersionPolicy.normalizeLayers(definitions);
        String actualHash = SceneVersionPolicy.contentHash(
                sceneDefinition, normalized, first.defaultCameraJson(),
                first.fallbackViewportJson(), layerOptions);
        if (!actualHash.equals(first.contentHash())) {
            throw new IllegalArgumentException("场景运行摘要与发布摘要不一致");
        }

        return new SceneRuntimeResponse.RuntimeSnapshot(
                first.sceneId(), first.sceneCode(), first.sceneName(), first.versionId(),
                first.revisionNumber(), first.schemaVersion(), first.contentHash(),
                first.coordinateSystemId(), first.coordinateMode(),
                first.originX(), first.originY(), first.originZ(),
                first.headingDegrees(), first.pitchDegrees(), first.rollDegrees(),
                first.sceneScale(), readObject(first.defaultCameraJson()),
                readObject(first.fallbackViewportJson()), first.publishedAt(),
                first.layerCount(), layers);
    }

    private LayerBuildResult buildLayer(UUID siteId, SceneMapper.SceneRuntimeRow row) {
        requireCompleteLayer(row);
        SceneVersionPolicy.LayerType layerType =
                SceneVersionPolicy.LayerType.valueOf(row.layerType());
        SceneAssetPolicy.SceneAssetType assetType = null;
        SceneRuntimeResponse.RuntimeAsset runtimeAsset = null;
        if (row.assetId() != null) {
            if (row.assetType() == null || row.assetStatus() == null
                    || !READABLE_ASSET_STATUSES.contains(row.assetStatus())
                    || row.assetContentDigest() == null || row.assetEntryPath() == null
                    || row.assetFileCount() == null || row.assetFileCount() < 1) {
                throw new IllegalArgumentException("场景静态资源入口不完整");
            }
            assetType = SceneAssetPolicy.SceneAssetType.valueOf(row.assetType());
            verifyStorage(row.assetContentDigest(), row.assetEntryPath());
            runtimeAsset = new SceneRuntimeResponse.RuntimeAsset(
                    row.assetId(), row.assetType(), row.assetContentDigest(),
                    row.assetFileCount(), entryUrl(siteId, row));
        }

        SceneVersionPolicy.LayerDefinition definition =
                new SceneVersionPolicy.LayerDefinition(
                        row.layerCode(), row.orderNumber(), layerType,
                        row.assetId(), assetType, row.visible(), row.opacity(),
                        row.minimumDistance(), row.maximumDistance(),
                        new SceneVersionPolicy.Vector3(
                                row.offsetX(), row.offsetY(), row.offsetZ()),
                        new SceneVersionPolicy.Rotation(
                                row.layerHeadingDegrees(), row.layerPitchDegrees(),
                                row.layerRollDegrees()),
                        row.layerScale(), row.maxItems());
        SceneRuntimeResponse.RuntimeLayer response = new SceneRuntimeResponse.RuntimeLayer(
                row.layerId(), row.layerCode(), row.orderNumber(), row.layerType(),
                row.visible(), row.opacity(), row.minimumDistance(), row.maximumDistance(),
                row.offsetX(), row.offsetY(), row.offsetZ(),
                row.layerHeadingDegrees(), row.layerPitchDegrees(), row.layerRollDegrees(),
                row.layerScale(), row.maxItems(), readObject(row.optionsJson()), runtimeAsset);
        return new LayerBuildResult(response, definition);
    }

    private void requireCompleteHeader(SceneMapper.SceneRuntimeRow row) {
        Objects.requireNonNull(row.sceneId(), "默认场景标识不能为空");
        Objects.requireNonNull(row.sceneCode(), "默认场景编码不能为空");
        Objects.requireNonNull(row.sceneName(), "默认场景名称不能为空");
        Objects.requireNonNull(row.versionId(), "发布版本标识不能为空");
        Objects.requireNonNull(row.coordinateSystemId(), "场景坐标系不能为空");
        Objects.requireNonNull(row.revisionNumber(), "发布修订号不能为空");
        Objects.requireNonNull(row.schemaVersion(), "快照结构版本不能为空");
        Objects.requireNonNull(row.coordinateMode(), "场景坐标模式不能为空");
        Objects.requireNonNull(row.originX(), "场景原点 X 不能为空");
        Objects.requireNonNull(row.originY(), "场景原点 Y 不能为空");
        Objects.requireNonNull(row.originZ(), "场景原点 Z 不能为空");
        Objects.requireNonNull(row.headingDegrees(), "场景航向角不能为空");
        Objects.requireNonNull(row.pitchDegrees(), "场景俯仰角不能为空");
        Objects.requireNonNull(row.rollDegrees(), "场景横滚角不能为空");
        Objects.requireNonNull(row.sceneScale(), "场景缩放不能为空");
        Objects.requireNonNull(row.defaultCameraJson(), "默认相机不能为空");
        Objects.requireNonNull(row.fallbackViewportJson(), "二维视口不能为空");
        Objects.requireNonNull(row.contentHash(), "发布摘要不能为空");
        Objects.requireNonNull(row.publishedAt(), "发布时间不能为空");
        Objects.requireNonNull(row.layerCount(), "图层数量不能为空");
    }

    private void requireCompleteLayer(SceneMapper.SceneRuntimeRow row) {
        Objects.requireNonNull(row.layerId(), "图层标识不能为空");
        Objects.requireNonNull(row.layerCode(), "图层编码不能为空");
        Objects.requireNonNull(row.orderNumber(), "图层顺序不能为空");
        Objects.requireNonNull(row.layerType(), "图层类型不能为空");
        Objects.requireNonNull(row.visible(), "图层可见性不能为空");
        Objects.requireNonNull(row.opacity(), "图层透明度不能为空");
        Objects.requireNonNull(row.minimumDistance(), "图层最小距离不能为空");
        Objects.requireNonNull(row.maximumDistance(), "图层最大距离不能为空");
        Objects.requireNonNull(row.offsetX(), "图层偏移 X 不能为空");
        Objects.requireNonNull(row.offsetY(), "图层偏移 Y 不能为空");
        Objects.requireNonNull(row.offsetZ(), "图层偏移 Z 不能为空");
        Objects.requireNonNull(row.layerHeadingDegrees(), "图层航向角不能为空");
        Objects.requireNonNull(row.layerPitchDegrees(), "图层俯仰角不能为空");
        Objects.requireNonNull(row.layerRollDegrees(), "图层横滚角不能为空");
        Objects.requireNonNull(row.layerScale(), "图层缩放不能为空");
        Objects.requireNonNull(row.maxItems(), "图层对象上限不能为空");
        Objects.requireNonNull(row.optionsJson(), "图层选项不能为空");
    }

    private void validateSameSnapshot(
            SceneMapper.SceneRuntimeRow expected,
            SceneMapper.SceneRuntimeRow actual
    ) {
        if (!Objects.equals(expected.sceneId(), actual.sceneId())
                || !Objects.equals(expected.versionId(), actual.versionId())
                || !Objects.equals(expected.layerCount(), actual.layerCount())) {
            throw new IllegalArgumentException("场景运行查询混入其他快照");
        }
    }

    private JsonNode readObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("场景运行 JSON 必须为对象");
            }
            return node;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("场景运行 JSON 无法解析", exception);
        }
    }

    private void verifyStorage(String contentDigest, String entryPath) {
        try {
            storage.resolve(contentDigest, entryPath);
        } catch (IOException | IllegalArgumentException exception) {
            throw new StorageUnavailableException(exception);
        }
    }

    private String entryUrl(UUID siteId, SceneMapper.SceneRuntimeRow row) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(
                "/api/v1/mine/sites/{siteId}/scene-assets/{assetId}/files");
        for (String segment : row.assetEntryPath().split("/")) {
            builder.pathSegment(segment);
        }
        return builder.buildAndExpand(siteId, row.assetId()).encode().toUriString();
    }

    private SceneRuntimeResponse degraded(UUID siteId, String reason) {
        return new SceneRuntimeResponse(siteId, "DEGRADED", reason, null);
    }

    /** 已组装的运行图层和摘要定义。 */
    private record LayerBuildResult(
            SceneRuntimeResponse.RuntimeLayer response,
            SceneVersionPolicy.LayerDefinition definition
    ) {
    }

    /** 标记内容寻址入口暂时不可读取。 */
    private static final class StorageUnavailableException extends RuntimeException {

        private StorageUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
