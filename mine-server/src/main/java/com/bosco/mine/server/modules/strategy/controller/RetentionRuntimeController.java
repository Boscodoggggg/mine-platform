package com.bosco.mine.server.modules.strategy.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.modules.strategy.service.RetentionGroupChangeGuard;
import com.bosco.mine.server.modules.strategy.service.RetentionRuntimeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
                request.productId(), request.strategyVersionId(),
                request.scopeType(), request.scopeId()));
    }

    /**
     * 预览设备组成员、优先级或归档变化间接激活保留策略后的删除影响。
     *
     * @param request 设备组拟变更请求
     * @return 是否需要一次性凭证及逐设备影响
     */
    @PostMapping("/retention-group-change-previews")
    @SaCheckPermission({"device:group:edit", "device:strategy:publish"})
    public RetentionRuntimeService.GroupChangePreviewView previewGroupChange(
            @Valid @RequestBody GroupChangePreviewRequest request
    ) {
        return service.previewGroupChange(new RetentionRuntimeService.GroupChangePreviewCommand(
                request.groupId(), request.changeType(), request.groupVersion(),
                request.deviceIds(), request.nextPriority()));
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
     * @param strategyVersionId 已保存策略版本标识
     * @param scopeType         作用域类型
     * @param scopeId           作用域标识
     */
    public record RetentionPreviewRequest(
            @NotNull UUID productId,
            @NotNull UUID strategyVersionId,
            @NotNull StrategyScopeType scopeType,
            @NotNull UUID scopeId
    ) {
    }

    /**
     * 设备组拟变更保留影响预览请求。
     *
     * @param groupId      设备组标识
     * @param changeType   成员、优先级或归档变化
     * @param groupVersion 设备组乐观锁版本
     * @param deviceIds    成员变化后的完整设备集合
     * @param nextPriority 优先级变化后的值
     */
    public record GroupChangePreviewRequest(
            @NotNull UUID groupId,
            @NotNull RetentionGroupChangeGuard.ChangeType changeType,
            @Min(0) long groupVersion,
            @Size(max = 5000) List<@NotNull UUID> deviceIds,
            @Min(0) @Max(1000) Integer nextPriority
    ) {
    }
}
