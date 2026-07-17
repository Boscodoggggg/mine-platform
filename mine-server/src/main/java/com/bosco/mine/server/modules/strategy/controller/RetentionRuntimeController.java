package com.bosco.mine.server.modules.strategy.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.modules.strategy.service.RetentionRuntimeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提供数据保留影响预览和最近执行事实只读接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class RetentionRuntimeController {

    private final RetentionRuntimeService service;

    /**
     * 创建数据保留运行查询控制器。
     *
     * @param service 数据保留运行查询服务
     */
    public RetentionRuntimeController(RetentionRuntimeService service) {
        this.service = service;
    }

    /**
     * 预览拟发布保留策略对目标作用域两个数据集的影响。
     *
     * @param request 影响预览请求
     * @return 带数据库统计时点和上限标记的影响
     */
    @PostMapping("/retention-previews")
    @SaCheckPermission("device:strategy:view")
    public RetentionRuntimeService.RetentionPreviewView preview(
            @Valid @RequestBody RetentionPreviewRequest request
    ) {
        return service.preview(new RetentionRuntimeService.PreviewCommand(
                request.productId(), request.scopeType(), request.scopeId(), request.policy()));
    }

    /**
     * 查询最近数据保留执行事实。
     *
     * @param limit 最大返回数量
     * @return 最近执行事实
     */
    @GetMapping("/retention-executions")
    @SaCheckPermission("device:strategy:view")
    public List<RetentionRuntimeService.ExecutionView> recentExecutions(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.recentExecutions(limit);
    }

    /**
     * 数据保留影响预览请求。
     *
     * @param productId 产品标识
     * @param scopeType 作用域类型
     * @param scopeId   作用域标识
     * @param policy    拟发布的原子保留策略对象
     */
    public record RetentionPreviewRequest(
            @NotNull UUID productId,
            @NotNull StrategyScopeType scopeType,
            @NotNull UUID scopeId,
            @NotEmpty @Size(max = 4)
            Map<@NotBlank @Size(max = 64) String, Object> policy
    ) {
    }
}
