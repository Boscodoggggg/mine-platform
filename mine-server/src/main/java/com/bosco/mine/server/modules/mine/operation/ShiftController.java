package com.bosco.mine.server.modules.mine.operation;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftActionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionResponse;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceResponse;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 发布矿区班次模板、实例和显式生命周期动作接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}")
public class ShiftController {

    private final ShiftQueryService queryService;
    private final ShiftCommandService commandService;
    private final OperationActionIdempotencyService idempotencyService;

    /**
     * 创建班次管理控制器。
     *
     * @param queryService 班次查询服务
     * @param commandService 班次命令服务
     * @param idempotencyService 动作幂等服务
     */
    public ShiftController(
            ShiftQueryService queryService,
            ShiftCommandService commandService,
            OperationActionIdempotencyService idempotencyService
    ) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.idempotencyService = idempotencyService;
    }

    /** 查询矿区班次模板。 */
    @GetMapping("/shift-definitions")
    @SaCheckPermission("mine:operation:view")
    public List<ShiftDefinitionResponse> findDefinitions(
            @PathVariable UUID siteId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) long offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return queryService.findDefinitions(siteId, status, offset, limit);
    }

    /** 新建矿区班次模板。 */
    @PostMapping("/shift-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("mine:shift:edit")
    public ShiftDefinitionResponse createDefinition(
            @PathVariable UUID siteId,
            @Valid @RequestBody ShiftDefinitionRequest request
    ) {
        return commandService.createDefinition(siteId, request);
    }

    /** 查询单个班次模板。 */
    @GetMapping("/shift-definitions/{definitionId}")
    @SaCheckPermission("mine:operation:view")
    public ShiftDefinitionResponse findDefinition(
            @PathVariable UUID siteId,
            @PathVariable UUID definitionId
    ) {
        return queryService.findDefinition(siteId, definitionId);
    }

    /** 使用乐观版本修改活动班次模板。 */
    @PatchMapping("/shift-definitions/{definitionId}")
    @SaCheckPermission("mine:shift:edit")
    public ShiftDefinitionResponse updateDefinition(
            @PathVariable UUID siteId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody ShiftDefinitionRequest request
    ) {
        return commandService.updateDefinition(siteId, definitionId, request);
    }

    /** 使用乐观版本归档班次模板。 */
    @PostMapping("/shift-definitions/{definitionId}/archive")
    @SaCheckPermission("mine:shift:edit")
    public ShiftDefinitionResponse archiveDefinition(
            @PathVariable UUID siteId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody ShiftActionRequest request
    ) {
        return commandService.archiveDefinition(siteId, definitionId, request.version());
    }

    /** 按营业日期范围查询矿区班次实例。 */
    @GetMapping("/shift-instances")
    @SaCheckPermission("mine:operation:view")
    public List<ShiftInstanceResponse> findInstances(
            @PathVariable UUID siteId,
            @RequestParam LocalDate fromDate,
            @RequestParam LocalDate toDate,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) long offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return queryService.findInstances(siteId, fromDate, toDate, status, offset, limit);
    }

    /** 新建计划班次实例。 */
    @PostMapping("/shift-instances")
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("mine:shift:edit")
    public ShiftInstanceResponse createInstance(
            @PathVariable UUID siteId,
            @Valid @RequestBody ShiftInstanceRequest request
    ) {
        return commandService.createInstance(siteId, request);
    }

    /** 查询单个班次实例。 */
    @GetMapping("/shift-instances/{shiftId}")
    @SaCheckPermission("mine:operation:view")
    public ShiftInstanceResponse findInstance(
            @PathVariable UUID siteId,
            @PathVariable UUID shiftId
    ) {
        return queryService.findInstance(siteId, shiftId);
    }

    /** 使用持久幂等键开启计划班次。 */
    @PostMapping("/shift-instances/{shiftId}/open")
    @SaCheckPermission("mine:shift:edit")
    public ShiftInstanceResponse openInstance(
            @PathVariable UUID siteId,
            @PathVariable UUID shiftId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody ShiftActionRequest request
    ) {
        return idempotencyService.execute(
                siteId, idempotencyKey, "OPEN_SHIFT", "SHIFT_INSTANCE", shiftId,
                request, ShiftInstanceResponse.class,
                () -> commandService.openInstance(siteId, shiftId, request.version()));
    }

    /** 使用持久幂等键和独立高风险权限关闭班次。 */
    @PostMapping("/shift-instances/{shiftId}/close")
    @SaCheckPermission("mine:shift:close")
    public ShiftInstanceResponse closeInstance(
            @PathVariable UUID siteId,
            @PathVariable UUID shiftId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody ShiftActionRequest request
    ) {
        return idempotencyService.execute(
                siteId, idempotencyKey, "CLOSE_SHIFT", "SHIFT_INSTANCE", shiftId,
                request, ShiftInstanceResponse.class,
                () -> commandService.closeInstance(siteId, shiftId, request.version()));
    }

    /** 使用持久幂等键取消尚未开启的计划班次。 */
    @PostMapping("/shift-instances/{shiftId}/cancel")
    @SaCheckPermission("mine:shift:edit")
    public ShiftInstanceResponse cancelInstance(
            @PathVariable UUID siteId,
            @PathVariable UUID shiftId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody ShiftActionRequest request
    ) {
        return idempotencyService.execute(
                siteId, idempotencyKey, "CANCEL_SHIFT", "SHIFT_INSTANCE", shiftId,
                request, ShiftInstanceResponse.class,
                () -> commandService.cancelInstance(
                        siteId, shiftId, request.version(), request.reason()));
    }
}
