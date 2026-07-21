package com.bosco.mine.server.modules.strategy.controller;

import com.bosco.mine.server.modules.strategy.service.StrategyRuntimeSnapshotService;
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
import java.util.UUID;

/**
 * 向受信任运行服务提供不可变数据质量策略增量快照。
 */
@RestController
@Validated
@RequestMapping("/internal/v1/strategy-runtime-snapshots")
public class InternalStrategyRuntimeSnapshotController {

    private final StrategyRuntimeSnapshotService service;
    private final ObjectMapper objectMapper;

    /**
     * 创建内部策略运行快照控制器。
     *
     * @param service 策略运行快照服务
     * @param objectMapper JSON 序列化器
     */
    public InternalStrategyRuntimeSnapshotController(
            StrategyRuntimeSnapshotService service,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用设备标识游标读取指定不可变快照。
     *
     * @param snapshotId 快照标识
     * @param afterDeviceId 上一页最后设备标识
     * @param limit 单页最大条目数
     * @return 结构化快照页
     */
    @GetMapping("/{id}")
    public SnapshotResponse findSnapshot(
            @PathVariable("id") UUID snapshotId,
            @RequestParam(required = false) UUID afterDeviceId,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit
    ) {
        StrategyRuntimeSnapshotService.SnapshotPage page =
                service.findSnapshotPage(snapshotId, afterDeviceId, limit);
        return new SnapshotResponse(
                page.snapshotId(), page.productId(), page.strategyType(), page.runtimeRevision(),
                page.changeReason().name(), page.itemCount(), page.contentHash(), page.createdAt(),
                page.items().stream().map(item -> new SnapshotItemResponse(
                        item.deviceId(), readValues(item.valuesJson()), item.contentHash())).toList(),
                page.nextAfterDeviceId()
        );
    }

    private JsonNode readValues(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的策略运行快照 JSON 无法解析", exception);
        }
    }

    /** 运行服务拉取的结构化策略快照页。 */
    public record SnapshotResponse(
            UUID snapshotId,
            UUID productId,
            String strategyType,
            long runtimeRevision,
            String changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt,
            List<SnapshotItemResponse> items,
            UUID nextAfterDeviceId
    ) {
        /** 冻结响应条目。 */
        public SnapshotResponse {
            items = List.copyOf(items);
        }
    }

    /** 结构化单设备有效策略或删除条目。 */
    public record SnapshotItemResponse(
            UUID deviceId,
            JsonNode values,
            String contentHash
    ) {
    }
}
