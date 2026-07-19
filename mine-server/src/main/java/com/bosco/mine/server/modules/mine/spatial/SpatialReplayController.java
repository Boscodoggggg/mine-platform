package com.bosco.mine.server.modules.mine.spatial;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialReplayRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialReplayResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 提供管理员显式创建异步空间历史重放任务的接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}/spatial-events/replays")
@ConditionalOnProperty(prefix = "mine.spatial-replay", name = "enabled", havingValue = "true")
public class SpatialReplayController {

    private final SpatialReplayService replayService;

    /**
     * 创建空间重放控制器。
     *
     * @param replayService 重放登记服务
     */
    public SpatialReplayController(SpatialReplayService replayService) {
        this.replayService = replayService;
    }

    /**
     * 登记一个异步重放任务并立即返回接受结果。
     *
     * @param siteId 矿区标识
     * @param idempotencyKey 幂等键
     * @param request 重放请求
     * @return HTTP 202 与重放任务
     */
    @PostMapping
    @SaCheckPermission("mine:spatial:replay")
    public ResponseEntity<SpatialReplayResponse> create(
            @PathVariable UUID siteId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
            String idempotencyKey,
            @Valid @RequestBody SpatialReplayRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(replayService.create(siteId, idempotencyKey, request));
    }
}
