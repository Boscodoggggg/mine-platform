package com.bosco.mine.server.modules.configuration.controller;

import com.bosco.mine.server.modules.configuration.service.ProtocolConfigurationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * 向受信任运行节点提供指定发布的完整非敏感配置快照。
 */
@RestController
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
}
