package com.bosco.mine.server.modules.mine.location;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 向通过服务令牌认证的定位运行服务提供不可变快照。
 */
@RestController
@Validated
@RequestMapping("/api/v1/internal/location-snapshots")
public class LocationSnapshotController {

    private final LocationSnapshotService service;
    private final ObjectMapper objectMapper;

    /**
     * 创建定位运行快照内部控制器。
     *
     * @param service 快照服务
     * @param objectMapper JSON 映射器
     */
    public LocationSnapshotController(
            LocationSnapshotService service,
            ObjectMapper objectMapper
    ) {
        this.service = Objects.requireNonNull(service, "定位快照服务不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 映射器不能为空");
    }

    /**
     * 读取指定矿区最新快照元数据。
     *
     * @param siteId 矿区标识
     * @return 最新快照元数据
     */
    @GetMapping("/latest")
    public SnapshotMetadataResponse latest(@RequestParam UUID siteId) {
        LocationSnapshotService.SnapshotMetadata snapshot = service.findLatest(siteId);
        return new SnapshotMetadataResponse(
                snapshot.snapshotId(), snapshot.organizationId(), snapshot.siteId(),
                snapshot.revision(), snapshot.changeReason().name(), snapshot.itemCount(),
                snapshot.contentHash(), snapshot.createdAt());
    }

    /**
     * 使用稳定条目键游标分页读取指定不可变快照。
     *
     * @param snapshotId 快照标识
     * @param afterItemKey 上一页最后条目键
     * @param limit 单页条目数
     * @return 完整元数据、当前条目和下一页游标
     */
    @GetMapping("/{id}")
    public SnapshotPageResponse page(
            @PathVariable("id") UUID snapshotId,
            @RequestParam(required = false) String afterItemKey,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit
    ) {
        LocationSnapshotService.SnapshotPage page = service.findSnapshotPage(
                snapshotId, afterItemKey, limit);
        return new SnapshotPageResponse(
                page.snapshotId(), page.organizationId(), page.siteId(), page.revision(),
                page.changeReason().name(), page.itemCount(), page.contentHash(), page.createdAt(),
                page.items().stream().map(item -> new SnapshotItemResponse(
                        item.itemKey(), item.itemType(), readContent(item.contentJson()),
                        item.contentHash())).toList(),
                page.nextAfterItemKey());
    }

    private JsonNode readContent(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的定位快照 JSON 无法解析", exception);
        }
    }

    /** 最新定位运行快照元数据响应。 */
    public record SnapshotMetadataResponse(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            String changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt
    ) {
    }

    /** 定位运行快照分页响应。 */
    public record SnapshotPageResponse(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            String changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt,
            List<SnapshotItemResponse> items,
            String nextAfterItemKey
    ) {
        /** 冻结当前响应条目。 */
        public SnapshotPageResponse {
            items = List.copyOf(items);
        }
    }

    /** 单个定位运行快照条目响应。 */
    public record SnapshotItemResponse(
            String itemKey,
            String itemType,
            JsonNode content,
            String contentHash
    ) {
    }
}
