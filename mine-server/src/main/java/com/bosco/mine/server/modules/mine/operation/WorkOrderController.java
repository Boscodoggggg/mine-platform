package com.bosco.mine.server.modules.mine.operation;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderActionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderAssignmentRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 发布运输作业单、调度分配和显式生命周期动作接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}")
public class WorkOrderController {

    private final WorkOrderQueryService queryService;
    private final WorkOrderCommandService commandService;
    private final OperationActionIdempotencyService idempotencyService;

    /**
     * 创建运输作业单管理控制器。
     *
     * @param queryService 作业单查询服务
     * @param commandService 作业单命令服务
     * @param idempotencyService 动作幂等服务
     */
    public WorkOrderController(
            WorkOrderQueryService queryService,
            WorkOrderCommandService commandService,
            OperationActionIdempotencyService idempotencyService
    ) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.idempotencyService = idempotencyService;
    }

    /** 分页查询矿区运输作业单。 */
    @GetMapping("/work-orders")
    @SaCheckPermission("mine:operation:view")
    public List<WorkOrderResponse> findWorkOrders(
            @PathVariable UUID siteId,
            @RequestParam(required = false) UUID shiftInstanceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) long offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return queryService.findWorkOrders(siteId, shiftInstanceId, status, offset, limit);
    }

    /** 新建运输作业单草稿。 */
    @PostMapping("/work-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("mine:work-order:edit")
    public WorkOrderResponse createWorkOrder(
            @PathVariable UUID siteId,
            @Valid @RequestBody WorkOrderRequest request
    ) {
        return commandService.createWorkOrder(siteId, request);
    }

    /** 查询单张运输作业单和全部调度分配。 */
    @GetMapping("/work-orders/{workOrderId}")
    @SaCheckPermission("mine:operation:view")
    public WorkOrderResponse findWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId
    ) {
        return queryService.findWorkOrder(siteId, workOrderId);
    }

    /** 使用乐观版本修改运输作业单草稿。 */
    @PatchMapping("/work-orders/{workOrderId}")
    @SaCheckPermission("mine:work-order:edit")
    public WorkOrderResponse updateWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @Valid @RequestBody WorkOrderRequest request
    ) {
        return commandService.updateWorkOrder(siteId, workOrderId, request);
    }

    /** 追加经过车型、司机任职资质和时间冲突校验的调度分配。 */
    @PostMapping("/work-orders/{workOrderId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("mine:work-order:dispatch")
    public WorkOrderResponse assignWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @Valid @RequestBody WorkOrderAssignmentRequest request
    ) {
        return commandService.assignWorkOrder(siteId, workOrderId, request);
    }

    /** 发布并冻结作业单计划、空间版本与调度显示快照。 */
    @PostMapping("/work-orders/{workOrderId}/release")
    @SaCheckPermission("mine:work-order:dispatch")
    public WorkOrderResponse releaseWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody WorkOrderActionRequest request
    ) {
        return executeAction(
                siteId, workOrderId, idempotencyKey, "RELEASE_WORK_ORDER", request,
                () -> commandService.releaseWorkOrder(siteId, workOrderId, request));
    }

    /** 启动已发布运输作业单。 */
    @PostMapping("/work-orders/{workOrderId}/start")
    @SaCheckPermission("mine:work-order:dispatch")
    public WorkOrderResponse startWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody WorkOrderActionRequest request
    ) {
        return executeAction(
                siteId, workOrderId, idempotencyKey, "START_WORK_ORDER", request,
                () -> commandService.startWorkOrder(siteId, workOrderId, request));
    }

    /** 暂停运行中的运输作业单。 */
    @PostMapping("/work-orders/{workOrderId}/pause")
    @SaCheckPermission("mine:work-order:dispatch")
    public WorkOrderResponse pauseWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody WorkOrderActionRequest request
    ) {
        return executeAction(
                siteId, workOrderId, idempotencyKey, "PAUSE_WORK_ORDER", request,
                () -> commandService.pauseWorkOrder(siteId, workOrderId, request));
    }

    /** 恢复已暂停的运输作业单。 */
    @PostMapping("/work-orders/{workOrderId}/resume")
    @SaCheckPermission("mine:work-order:dispatch")
    public WorkOrderResponse resumeWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody WorkOrderActionRequest request
    ) {
        return executeAction(
                siteId, workOrderId, idempotencyKey, "RESUME_WORK_ORDER", request,
                () -> commandService.resumeWorkOrder(siteId, workOrderId, request));
    }

    /** 完成运行或暂停中的运输作业单。 */
    @PostMapping("/work-orders/{workOrderId}/complete")
    @SaCheckPermission("mine:work-order:dispatch")
    public WorkOrderResponse completeWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody WorkOrderActionRequest request
    ) {
        return executeAction(
                siteId, workOrderId, idempotencyKey, "COMPLETE_WORK_ORDER", request,
                () -> commandService.completeWorkOrder(siteId, workOrderId, request));
    }

    /** 取消草稿或已发布运输作业单并保留原因。 */
    @PostMapping("/work-orders/{workOrderId}/cancel")
    @SaCheckPermission("mine:work-order:dispatch")
    public WorkOrderResponse cancelWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody WorkOrderActionRequest request
    ) {
        return executeAction(
                siteId, workOrderId, idempotencyKey, "CANCEL_WORK_ORDER", request,
                () -> commandService.cancelWorkOrder(siteId, workOrderId, request));
    }

    /** 异常终止运行或暂停中的运输作业单并保留原因。 */
    @PostMapping("/work-orders/{workOrderId}/abort")
    @SaCheckPermission("mine:work-order:dispatch")
    public WorkOrderResponse abortWorkOrder(
            @PathVariable UUID siteId,
            @PathVariable UUID workOrderId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody WorkOrderActionRequest request
    ) {
        return executeAction(
                siteId, workOrderId, idempotencyKey, "ABORT_WORK_ORDER", request,
                () -> commandService.abortWorkOrder(siteId, workOrderId, request));
    }

    private WorkOrderResponse executeAction(
            UUID siteId,
            UUID workOrderId,
            String idempotencyKey,
            String actionType,
            WorkOrderActionRequest request,
            Supplier<WorkOrderResponse> action
    ) {
        return idempotencyService.execute(
                siteId, idempotencyKey, actionType, "WORK_ORDER", workOrderId,
                request, WorkOrderResponse.class, action);
    }
}
