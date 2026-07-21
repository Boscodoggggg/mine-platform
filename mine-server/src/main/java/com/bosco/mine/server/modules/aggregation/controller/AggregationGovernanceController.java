package com.bosco.mine.server.modules.aggregation.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.aggregation.dto.AggregationExecutionResponse;
import com.bosco.mine.server.modules.aggregation.dto.AggregationRecomputeRequest;
import com.bosco.mine.server.modules.aggregation.dto.AggregationRecomputeResponse;
import com.bosco.mine.server.modules.aggregation.service.AggregationGovernanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供聚合重算登记、请求历史和运行历史接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "mine.telemetry-query", name = "enabled", havingValue = "true")
public class AggregationGovernanceController {

    private final AggregationGovernanceService service;

    /**
     * 创建聚合治理控制器。
     *
     * @param service 聚合治理应用服务
     */
    public AggregationGovernanceController(AggregationGovernanceService service) {
        this.service = service;
    }

    /**
     * 登记一个异步聚合重算请求。
     *
     * @param idempotencyKey 调用方幂等键
     * @param request 重算范围和规则键
     * @return 已接受或幂等重放的请求事实
     */
    @PostMapping("/aggregation-recomputations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SaCheckPermission("governance:aggregation:execute")
    public AggregationRecomputeResponse createRecomputation(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody AggregationRecomputeRequest request
    ) {
        return service.create(request, idempotencyKey);
    }

    /**
     * 查询最近自动聚合执行事实。
     *
     * @param limit 最大返回数量
     * @return 自动聚合执行历史
     */
    @GetMapping("/aggregation-executions")
    @SaCheckPermission("governance:aggregation:view")
    public List<AggregationExecutionResponse> recentExecutions(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.recentExecutions(limit);
    }

    /**
     * 查询最近显式聚合重算请求事实。
     *
     * @param limit 最大返回数量
     * @return 显式重算请求历史
     */
    @GetMapping("/aggregation-recomputations")
    @SaCheckPermission("governance:aggregation:view")
    public List<AggregationRecomputeResponse> recentRecomputations(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.recentRequests(limit);
    }
}
