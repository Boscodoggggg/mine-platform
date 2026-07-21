package com.bosco.mine.server.modules.aggregation.controller;

import com.bosco.mine.server.modules.aggregation.dto.InternalAggregationRecomputeClaim;
import com.bosco.mine.server.modules.aggregation.dto.InternalAggregationRecomputeClaimRequest;
import com.bosco.mine.server.modules.aggregation.dto.InternalAggregationRecomputeCompletion;
import com.bosco.mine.server.modules.aggregation.service.AggregationGovernanceService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 为受信任的 Telemetry 实例提供显式聚合重算租约和终态回写。
 */
@Validated
@RestController
@RequestMapping("/internal/v1/aggregation-recomputations")
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class InternalAggregationRecomputeController {

    private final AggregationGovernanceService service;

    /**
     * 创建内部聚合重算控制器。
     *
     * @param service 聚合治理应用服务
     */
    public InternalAggregationRecomputeController(AggregationGovernanceService service) {
        this.service = service;
    }

    /**
     * 原子认领一个可执行请求。
     *
     * @param request 实例与租约参数
     * @return 当前租约或无内容
     */
    @PostMapping("/claim")
    public ResponseEntity<InternalAggregationRecomputeClaim> claim(
            @Valid @RequestBody InternalAggregationRecomputeClaimRequest request
    ) {
        return service.claimRecomputation(request.instanceId(), request.leaseDuration())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 使用当前租约令牌回写成功或失败终态。
     *
     * @param requestId 请求标识
     * @param completion 终态证据
     */
    @PostMapping("/{requestId}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(
            @PathVariable UUID requestId,
            @Valid @RequestBody InternalAggregationRecomputeCompletion completion
    ) {
        boolean completed = service.completeRecomputation(
                requestId, completion.executionId(), completion.claimToken(),
                completion.status(), completion.error());
        if (!completed) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "聚合重算租约已失效或请求已经完成");
        }
    }
}
