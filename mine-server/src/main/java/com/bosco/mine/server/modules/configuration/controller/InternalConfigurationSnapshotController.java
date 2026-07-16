package com.bosco.mine.server.modules.configuration.controller;

import com.bosco.mine.server.modules.configuration.service.ProtocolConfigurationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;

/**
 * 向受信任运行节点提供指定发布的完整非敏感配置快照。
 */
@RestController
@Validated
@RequestMapping("/internal/v1/config-releases")
public class InternalConfigurationSnapshotController {

    private final ProtocolConfigurationService service;
    private final ObjectMapper objectMapper;

    /**
     * 创建内部配置快照控制器。
     *
     * @param service 协议配置服务
     * @param objectMapper JSON 序列化器
     */
    public InternalConfigurationSnapshotController(
            ProtocolConfigurationService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取指定发布对应的完整配置快照。
     *
     * @param releaseId 配置发布标识
     * @return 运行节点快照
     */
    @GetMapping("/{id}")
    public SnapshotResponse findSnapshot(@PathVariable("id") UUID releaseId) {
        ProtocolConfigurationService.ReleaseSnapshot snapshot = service.findSnapshot(releaseId);
        return new SnapshotResponse(
                snapshot.releaseId(), snapshot.productId(), snapshot.packageId(),
                snapshot.releaseSequence(), snapshot.contentHash(), readSnapshot(snapshot.snapshotJson())
        );
    }

    /**
     * 接收运行节点对指定配置发布的加载结果。
     *
     * @param releaseId 配置发布标识
     * @param nodeId 运行节点稳定标识
     * @param request 节点加载状态
     * @return 空响应
     */
    @PostMapping("/{id}/nodes/{nodeId}")
    public ResponseEntity<Void> reportNodeStatus(
            @PathVariable("id") UUID releaseId,
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9._-]{1,128}$") String nodeId,
            @Valid @RequestBody NodeStatusRequest request
    ) {
        service.reportNodeStatus(
                releaseId,
                new ProtocolConfigurationService.NodeStatusCommand(
                        nodeId, request.nodeType(), request.status(),
                        request.actualHash(), request.message())
        );
        return ResponseEntity.noContent().build();
    }

    private JsonNode readSnapshot(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的配置快照 JSON 无法解析", exception);
        }
    }

    /** 运行节点拉取的结构化快照响应。 */
    public record SnapshotResponse(
            UUID releaseId,
            UUID productId,
            UUID packageId,
            long releaseSequence,
            String contentHash,
            JsonNode snapshot
    ) {
    }

    /** 运行节点配置加载状态请求。 */
    public record NodeStatusRequest(
            @NotBlank @Pattern(regexp = "GATEWAY|TELEMETRY|SERVER") String nodeType,
            @NotBlank @Pattern(regexp = "LOADED|FAILED") String status,
            @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String actualHash,
            @NotBlank @Size(max = 500) String message
    ) {
    }
}
