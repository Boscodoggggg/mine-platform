package com.bosco.mine.server.modules.mine.scene;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.dto.MineArchiveRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneLayerRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneResponse;
import com.bosco.mine.server.modules.mine.scene.dto.SceneVersionRequest;
import com.bosco.mine.server.modules.mine.scene.dto.SceneVersionResponse;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发布矿区稳定场景、草稿、图层整体替换和不可变发布接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}")
public class SceneController {

    private static final ErrorCode INVALID_LAYER_REQUEST =
            new ErrorCode("MINE-SCENE-400-001", "场景图层请求不合法");

    private final SceneQueryService queryService;
    private final SceneCommandService commandService;
    private final Validator validator;

    /**
     * 创建场景控制器。
     *
     * @param queryService 场景查询服务
     * @param commandService 场景命令服务
     * @param validator 请求校验器
     */
    public SceneController(
            SceneQueryService queryService,
            SceneCommandService commandService,
            Validator validator
    ) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.validator = validator;
    }

    /** 分页查询矿区稳定场景。 */
    @GetMapping("/scenes")
    @SaCheckPermission("mine:scene:view")
    public PageResponse<SceneResponse> findScenes(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findScenes(siteId, keyword, status, page, size);
    }

    /** 创建一个编码稳定的矿区场景。 */
    @PostMapping("/scenes")
    @SaCheckPermission("mine:scene:edit")
    public ResponseEntity<SceneResponse> createScene(
            @PathVariable UUID siteId,
            @Valid @RequestBody SceneRequest request
    ) {
        SceneResponse created = commandService.createScene(siteId, request);
        return ResponseEntity.created(sceneUri(siteId, created.id())).body(created);
    }

    /** 查询一个稳定场景。 */
    @GetMapping("/scenes/{sceneId}")
    @SaCheckPermission("mine:scene:view")
    public SceneResponse findScene(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId
    ) {
        return queryService.findScene(siteId, sceneId);
    }

    /** 使用乐观版本修改稳定场景名称和说明。 */
    @PatchMapping("/scenes/{sceneId}")
    @SaCheckPermission("mine:scene:edit")
    public SceneResponse updateScene(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @Valid @RequestBody SceneRequest request
    ) {
        return commandService.updateScene(siteId, sceneId, request);
    }

    /** 使用持久幂等键归档非默认场景。 */
    @PostMapping("/scenes/{sceneId}/archive")
    @SaCheckPermission("mine:scene:edit")
    public SceneResponse archiveScene(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        return commandService.archiveScene(
                siteId, sceneId, request.version(), idempotencyKey);
    }

    /** 使用持久幂等键切换矿区默认场景。 */
    @PostMapping("/scenes/{sceneId}/make-default")
    @SaCheckPermission("mine:scene:publish")
    public SceneResponse setDefault(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        return commandService.setDefault(
                siteId, sceneId, request.version(), idempotencyKey);
    }

    /** 分页查询场景历史版本及有序图层。 */
    @GetMapping("/scenes/{sceneId}/versions")
    @SaCheckPermission("mine:scene:view")
    public PageResponse<SceneVersionResponse> findVersions(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findVersions(siteId, sceneId, page, size);
    }

    /** 创建场景首个草稿。 */
    @PostMapping("/scenes/{sceneId}/versions")
    @SaCheckPermission("mine:scene:edit")
    public ResponseEntity<SceneVersionResponse> createDraft(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @Valid @RequestBody SceneVersionRequest request
    ) {
        SceneVersionResponse created = commandService.createDraft(siteId, sceneId, request);
        return ResponseEntity.created(versionUri(siteId, sceneId, created.id())).body(created);
    }

    /** 从当前已发布版本完整复制下一草稿。 */
    @PostMapping("/scenes/{sceneId}/versions/next-draft")
    @SaCheckPermission("mine:scene:edit")
    public ResponseEntity<SceneVersionResponse> createNextDraft(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId
    ) {
        SceneVersionResponse created = commandService.createNextDraft(siteId, sceneId);
        return ResponseEntity.created(versionUri(siteId, sceneId, created.id())).body(created);
    }

    /** 查询一个场景版本及其有序图层。 */
    @GetMapping("/scenes/{sceneId}/versions/{versionId}")
    @SaCheckPermission("mine:scene:view")
    public SceneVersionResponse findVersion(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @PathVariable UUID versionId
    ) {
        return queryService.findVersion(siteId, sceneId, versionId);
    }

    /** 使用乐观版本修改场景草稿坐标和视图。 */
    @PatchMapping("/scenes/{sceneId}/versions/{versionId}")
    @SaCheckPermission("mine:scene:edit")
    public SceneVersionResponse updateDraft(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @PathVariable UUID versionId,
            @Valid @RequestBody SceneVersionRequest request
    ) {
        return commandService.updateDraft(siteId, sceneId, versionId, request);
    }

    /** 使用乐观版本原子替换草稿的全部有序图层。 */
    @PutMapping("/scenes/{sceneId}/versions/{versionId}/layers")
    @SaCheckPermission("mine:scene:edit")
    public SceneVersionResponse replaceLayers(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @PathVariable UUID versionId,
            @RequestParam @Min(0) long version,
            @Valid @RequestBody @Size(max = 256) List<@Valid SceneLayerRequest> layers
    ) {
        validateLayerBatch(layers);
        return commandService.replaceLayers(siteId, sceneId, versionId, version, layers);
    }

    /** 使用持久幂等键发布并永久锁定场景版本。 */
    @PostMapping("/scenes/{sceneId}/versions/{versionId}/publish")
    @SaCheckPermission("mine:scene:publish")
    public SceneVersionResponse publish(
            @PathVariable UUID siteId,
            @PathVariable UUID sceneId,
            @PathVariable UUID versionId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        return commandService.publish(
                siteId, sceneId, versionId, request.version(), idempotencyKey);
    }

    private URI sceneUri(UUID siteId, UUID sceneId) {
        return UriComponentsBuilder.fromPath("/api/v1/mine/sites/{siteId}/scenes/{sceneId}")
                .buildAndExpand(siteId, sceneId).toUri();
    }

    private URI versionUri(UUID siteId, UUID sceneId, UUID versionId) {
        return UriComponentsBuilder.fromPath(
                        "/api/v1/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}")
                .buildAndExpand(siteId, sceneId, versionId).toUri();
    }

    /** 对数组请求中的每个图层执行显式校验，避免运行配置差异跳过级联约束。 */
    private void validateLayerBatch(List<SceneLayerRequest> layers) {
        if (layers == null || layers.size() > 256) {
            throw new MineException(INVALID_LAYER_REQUEST, Map.of());
        }
        for (SceneLayerRequest layer : layers) {
            if (layer == null || !validator.validate(layer).isEmpty()) {
                throw new MineException(INVALID_LAYER_REQUEST, Map.of());
            }
        }
    }
}
