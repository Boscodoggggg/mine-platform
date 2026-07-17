package com.bosco.mine.server.modules.strategy.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.modules.strategy.service.StrategyConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 提供策略草稿、不可变发布、回滚和有效值查询接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class StrategyConfigurationController {

    private final StrategyConfigurationService service;

    /**
     * 创建策略配置控制器。
     *
     * @param service 策略配置服务
     */
    public StrategyConfigurationController(StrategyConfigurationService service) {
        this.service = service;
    }

    /**
     * 查询产品下最近的策略版本。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param status       可选版本状态
     * @param limit        最大返回数量
     * @return 策略版本列表
     */
    @GetMapping("/strategy-versions")
    @SaCheckPermission("device:strategy:view")
    public List<StrategyConfigurationService.StrategyVersionView> findVersions(
            @RequestParam @NotNull UUID productId,
            @RequestParam @NotBlank @Size(max = 64) String strategyType,
            @RequestParam(required = false) @Pattern(regexp = "DRAFT|PUBLISHED") String status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return service.findVersions(productId, strategyType, status, limit);
    }

    /**
     * 查询单个策略版本资源。
     *
     * @param versionId 策略版本标识
     * @return 策略版本详情
     */
    @GetMapping("/strategy-versions/{id}")
    @SaCheckPermission("device:strategy:view")
    public StrategyConfigurationService.StrategyVersionView findVersion(
            @PathVariable("id") UUID versionId
    ) {
        return service.findVersion(versionId);
    }

    /**
     * 创建经过白名单校验的策略草稿。
     *
     * @param request 草稿创建请求
     * @return 新建策略版本和资源地址
     */
    @PostMapping("/strategy-versions")
    @SaCheckPermission("device:strategy:edit")
    public ResponseEntity<StrategyConfigurationService.StrategyVersionView> createDraft(
            @Valid @RequestBody CreateDraftRequest request
    ) {
        StrategyConfigurationService.StrategyVersionView created = service.createDraft(
                new StrategyConfigurationService.CreateDraftCommand(
                        request.productId(), request.strategyType(), request.values(), request.description()
                )
        );
        URI location = UriComponentsBuilder.fromPath("/api/v1/strategy-versions/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 使用乐观锁修改策略草稿。
     *
     * @param versionId 策略版本标识
     * @param request   草稿修改请求
     * @return 修改后的策略版本
     */
    @PutMapping("/strategy-versions/{id}")
    @SaCheckPermission("device:strategy:edit")
    public StrategyConfigurationService.StrategyVersionView updateDraft(
            @PathVariable("id") UUID versionId,
            @Valid @RequestBody UpdateDraftRequest request
    ) {
        return service.updateDraft(versionId, new StrategyConfigurationService.UpdateDraftCommand(
                request.values(), request.description(), request.version()
        ));
    }

    /**
     * 查询产品和策略类型最近的发布历史。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param limit        最大返回数量
     * @return 策略发布列表
     */
    @GetMapping("/strategy-releases")
    @SaCheckPermission("device:strategy:view")
    public List<StrategyConfigurationService.StrategyReleaseView> findReleases(
            @RequestParam @NotNull UUID productId,
            @RequestParam @NotBlank @Size(max = 64) String strategyType,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return service.findReleases(productId, strategyType, limit);
    }

    /**
     * 查询单个不可变策略发布资源。
     *
     * @param releaseId 策略发布标识
     * @return 策略发布详情
     */
    @GetMapping("/strategy-releases/{id}")
    @SaCheckPermission("device:strategy:view")
    public StrategyConfigurationService.StrategyReleaseView findRelease(
            @PathVariable("id") UUID releaseId
    ) {
        return service.findRelease(releaseId);
    }

    /**
     * 冲突预检通过后创建不可变策略发布。
     *
     * @param idempotencyKey 发布幂等键
     * @param request        发布请求
     * @return 新发布和资源地址
     */
    @PostMapping("/strategy-releases")
    @SaCheckPermission("device:strategy:publish")
    public ResponseEntity<StrategyConfigurationService.StrategyReleaseView> publish(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PublishRequest request
    ) {
        StrategyConfigurationService.StrategyReleaseView release = service.publish(
                new StrategyConfigurationService.PublishCommand(
                        request.strategyVersionId(), request.scopeType(), request.scopeId(),
                        request.reason(), idempotencyKey, request.retentionPreviewToken()
                )
        );
        return createdRelease(release);
    }

    /**
     * 复用历史不可变版本创建更高序号的回滚发布。
     *
     * @param releaseId     历史发布标识
     * @param idempotencyKey 回滚幂等键
     * @param request       回滚请求
     * @return 新回滚发布和资源地址
     */
    @PostMapping("/strategy-releases/{id}/rollback")
    @SaCheckPermission("device:strategy:publish")
    public ResponseEntity<StrategyConfigurationService.StrategyReleaseView> rollback(
            @PathVariable("id") UUID releaseId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody RollbackRequest request
    ) {
        return createdRelease(service.rollback(
                releaseId, request.reason(), idempotencyKey, request.retentionPreviewToken()));
    }

    /**
     * 查询单设备当前有效策略及逐字段来源。
     *
     * @param deviceId     设备标识
     * @param strategyType 策略类型
     * @return 有效策略解析结果
     */
    @GetMapping("/devices/{id}/effective-strategies/{type}")
    @SaCheckPermission("device:strategy:view")
    public StrategyConfigurationService.EffectiveStrategyView findEffective(
            @PathVariable("id") UUID deviceId,
            @PathVariable("type") @NotBlank @Size(max = 64) String strategyType
    ) {
        return service.findEffective(deviceId, strategyType);
    }

    private ResponseEntity<StrategyConfigurationService.StrategyReleaseView> createdRelease(
            StrategyConfigurationService.StrategyReleaseView release
    ) {
        URI location = UriComponentsBuilder.fromPath("/api/v1/strategy-releases/{id}")
                .buildAndExpand(release.id()).toUri();
        return ResponseEntity.created(location).body(release);
    }

    /**
     * 策略草稿创建请求。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param values       局部策略字段
     * @param description  版本说明
     */
    public record CreateDraftRequest(
            @NotNull UUID productId,
            @NotBlank @Size(max = 64) String strategyType,
            @NotEmpty @Size(max = 32) Map<@NotBlank @Size(max = 128) String, Object> values,
            @Size(max = 500) String description
    ) {
    }

    /**
     * 策略草稿修改请求。
     *
     * @param values      局部策略字段
     * @param description 版本说明
     * @param version     乐观锁版本号
     */
    public record UpdateDraftRequest(
            @NotEmpty @Size(max = 32) Map<@NotBlank @Size(max = 128) String, Object> values,
            @Size(max = 500) String description,
            @Min(0) long version
    ) {
    }

    /**
     * 策略发布请求。
     *
     * @param strategyVersionId 策略版本标识
     * @param scopeType        作用范围类型
     * @param scopeId          作用范围标识
     * @param reason           发布原因
     * @param retentionPreviewToken 数据保留影响预览凭证
     */
    public record PublishRequest(
            @NotNull UUID strategyVersionId,
            @NotNull StrategyScopeType scopeType,
            @NotNull UUID scopeId,
            @NotBlank @Size(max = 500) String reason,
            UUID retentionPreviewToken
    ) {
    }

    /**
     * 策略回滚请求。
     *
     * @param reason 回滚原因
     * @param retentionPreviewToken 数据保留影响预览凭证
     */
    public record RollbackRequest(
            @NotBlank @Size(max = 500) String reason,
            UUID retentionPreviewToken
    ) {
    }
}
