package com.bosco.mine.server.modules.configuration.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.configuration.service.ProtocolConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 提供协议黄金样本回放和设备配置发布管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ProtocolConfigurationController {

    private final ProtocolConfigurationService service;
    private final ObjectMapper objectMapper;

    /**
     * 创建协议配置控制器。
     *
     * @param service 协议配置服务
     * @param objectMapper JSON 序列化器
     */
    public ProtocolConfigurationController(
            ProtocolConfigurationService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询协议版本黄金样本。
     *
     * @param mappingVersionId 协议映射版本标识
     * @return 黄金样本集合
     */
    @GetMapping("/protocol-mapping-versions/{id}/samples")
    @SaCheckPermission("device:protocol:sample:view")
    public List<SampleResponse> findSamples(@PathVariable("id") UUID mappingVersionId) {
        return service.findSamples(mappingVersionId).stream().map(this::sampleResponse).toList();
    }

    /**
     * 原子替换协议草稿的黄金样本。
     *
     * @param mappingVersionId 协议映射版本标识
     * @param request 替换请求
     * @return 保存后的黄金样本
     */
    @PutMapping("/protocol-mapping-versions/{id}/samples")
    @SaCheckPermission("device:protocol:sample:edit")
    public List<SampleResponse> replaceSamples(
            @PathVariable("id") UUID mappingVersionId,
            @Valid @RequestBody ReplaceSamplesRequest request
    ) {
        List<ProtocolConfigurationService.SampleCommand> commands = request.samples().stream()
                .map(sample -> new ProtocolConfigurationService.SampleCommand(
                        sample.name(), sample.rawPayload().toString(), sample.expectedSuccess(),
                        sample.expectedProperties().toString(), sample.expectedErrorCode(), sample.sortOrder()
                ))
                .toList();
        return service.replaceSamples(mappingVersionId, request.version(), commands)
                .stream().map(this::sampleResponse).toList();
    }

    /**
     * 回放协议版本的全部黄金样本。
     *
     * @param mappingVersionId 协议映射版本标识
     * @return 结构化测试运行结果
     */
    @PostMapping("/protocol-mapping-versions/{id}/test")
    @SaCheckPermission("device:protocol:test")
    public ProtocolConfigurationService.TestRunView test(
            @PathVariable("id") UUID mappingVersionId
    ) {
        return service.test(mappingVersionId);
    }

    /**
     * 查询产品最近的配置发布历史。
     *
     * @param productId 产品标识
     * @param limit 最大返回数量
     * @return 配置发布摘要
     */
    @GetMapping("/config-releases")
    @SaCheckPermission("device:configuration:view")
    public List<ProtocolConfigurationService.ReleaseSummaryView> findReleases(
            @RequestParam UUID productId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return service.findReleases(productId, limit);
    }

    /**
     * 查询指定配置发布及运行节点加载状态。
     *
     * @param releaseId 发布标识
     * @return 配置发布详情
     */
    @GetMapping("/config-releases/{id}")
    @SaCheckPermission("device:configuration:view")
    public ProtocolConfigurationService.ReleaseDetailView findRelease(
            @PathVariable("id") UUID releaseId
    ) {
        return service.findRelease(releaseId);
    }

    /**
     * 通过发布门禁后创建配置发布。
     *
     * @param idempotencyKey 发布幂等键
     * @param request 发布请求
     * @return 新发布及资源地址
     */
    @PostMapping("/config-releases")
    @SaCheckPermission("device:configuration:publish")
    public ResponseEntity<ReleaseResponse> publish(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PublishRequest request
    ) {
        ProtocolConfigurationService.ReleaseView release = service.publish(
                new ProtocolConfigurationService.PublishCommand(
                        request.productId(), request.mappingVersionId(), request.reason(), idempotencyKey
                ));
        return created(release);
    }

    /**
     * 复用历史配置包创建更高序号的回滚发布。
     *
     * @param releaseId 作为回滚目标的历史发布标识
     * @param idempotencyKey 回滚幂等键
     * @param request 回滚请求
     * @return 新回滚发布及资源地址
     */
    @PostMapping("/config-releases/{id}/rollback")
    @SaCheckPermission("device:configuration:rollback")
    public ResponseEntity<ReleaseResponse> rollback(
            @PathVariable("id") UUID releaseId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody RollbackRequest request
    ) {
        return created(service.rollback(releaseId, request.reason(), idempotencyKey));
    }

    private ResponseEntity<ReleaseResponse> created(ProtocolConfigurationService.ReleaseView release) {
        URI location = UriComponentsBuilder.fromPath("/api/v1/config-releases/{id}")
                .buildAndExpand(release.id()).toUri();
        return ResponseEntity.created(location).body(ReleaseResponse.from(release));
    }

    private SampleResponse sampleResponse(ProtocolConfigurationService.SampleView sample) {
        return new SampleResponse(
                sample.id(), sample.name(), readJson(sample.rawPayloadJson()), sample.expectedSuccess(),
                readJson(sample.expectedPropertiesJson()), sample.expectedErrorCode(),
                sample.sortOrder(), sample.version()
        );
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的协议样本 JSON 无法解析", exception);
        }
    }

    /** 黄金样本批量替换请求。 */
    public record ReplaceSamplesRequest(
            @Min(0) long version,
            @NotEmpty @Size(max = 200) List<@Valid SampleRequest> samples
    ) {
    }

    /** 单条黄金样本请求。 */
    public record SampleRequest(
            @NotBlank @Size(max = 128) String name,
            @NotNull JsonNode rawPayload,
            boolean expectedSuccess,
            @NotNull JsonNode expectedProperties,
            @Size(max = 128) String expectedErrorCode,
            @Min(0) int sortOrder
    ) {
    }

    /** 黄金样本响应。 */
    public record SampleResponse(
            UUID id,
            String name,
            JsonNode rawPayload,
            boolean expectedSuccess,
            JsonNode expectedProperties,
            String expectedErrorCode,
            int sortOrder,
            long version
    ) {
    }

    /** 配置发布请求。 */
    public record PublishRequest(
            @NotNull UUID productId,
            @NotNull UUID mappingVersionId,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    /** 配置回滚请求。 */
    public record RollbackRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** 不暴露内部完整快照的发布响应。 */
    public record ReleaseResponse(
            UUID id,
            UUID productId,
            UUID packageId,
            long releaseSequence,
            String action,
            UUID rollbackOfReleaseId,
            String contentHash
    ) {
        private static ReleaseResponse from(ProtocolConfigurationService.ReleaseView release) {
            return new ReleaseResponse(
                    release.id(), release.productId(), release.packageId(), release.releaseSequence(),
                    release.action(), release.rollbackOfReleaseId(), release.contentHash()
            );
        }
    }
}
