package com.bosco.mine.server.modules.command.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.command.dto.CommandCreateRequest;
import com.bosco.mine.server.modules.command.dto.CommandResponse;
import com.bosco.mine.server.modules.command.dto.CommandTargetResponse;
import com.bosco.mine.server.modules.command.dto.CommandTemplateResponse;
import com.bosco.mine.server.modules.command.service.PublishedCommandTemplates;
import com.bosco.mine.server.modules.command.service.CommandQueryService;
import com.bosco.mine.server.modules.command.service.CommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提供低风险命令模板、下发和状态时间线查询接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CommandController {
    private final CommandService commandService;
    private final CommandQueryService queryService;

    /**
     * 创建命令控制器。
     *
     * @param commandService 命令写服务
     * @param queryService 命令查询服务
     */
    public CommandController(CommandService commandService, CommandQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** @return 当前已发布的低风险命令模板 */
    @GetMapping("/command-templates")
    @SaCheckPermission("command:template:view")
    public List<CommandTemplateResponse> templates() {
        return PublishedCommandTemplates.publishedTemplates();
    }

    /**
     * 分页查询可接收命令的启用设备，不要求设备台账管理权限。
     *
     * @param keyword 设备编码或名称关键词
     * @param page 页码
     * @param size 每页数量
     * @return 命令目标设备分页
     */
    @GetMapping("/command-targets")
    @SaCheckPermission("command:instance:view")
    public PageResponse<CommandTargetResponse> targets(
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size
    ) {
        return queryService.findTargets(keyword, page, size);
    }

    /**
     * 分页查询命令。
     *
     * @param deviceId 设备筛选
     * @param status 状态筛选
     * @param templateKey 模板筛选
     * @param page 页码
     * @param size 每页数量
     * @return 命令分页
     */
    @GetMapping("/commands")
    @SaCheckPermission("command:instance:view")
    public PageResponse<CommandResponse> findCommands(
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) @Pattern(regexp =
                    "CREATED|QUEUED|SENT|DEVICE_RECEIVED|SUCCEEDED|REFUSED|FAILED|EXPIRED|TIMEOUT|CANCELLED")
            String status,
            @RequestParam(required = false) @Size(max = 128) String templateKey,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return queryService.findCommands(deviceId, status, templateKey, page, size);
    }

    /**
     * 查询命令详情。
     *
     * @param commandId 命令标识
     * @return 命令详情
     */
    @GetMapping("/commands/{id}")
    @SaCheckPermission("command:instance:view")
    public CommandResponse findCommand(@PathVariable("id") UUID commandId) {
        return queryService.findById(commandId);
    }

    /**
     * 幂等创建并下发低风险命令。
     *
     * @param idempotencyKey 创建幂等键
     * @param httpRequest HTTP 请求
     * @param request 命令请求
     * @return 命令和资源地址
     */
    @PostMapping("/commands")
    @SaCheckPermission("command:instance:create")
    public ResponseEntity<CommandResponse> createCommand(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            HttpServletRequest httpRequest,
            @Valid @RequestBody CommandCreateRequest request
    ) {
        String traceId = String.valueOf(httpRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
        CommandResponse command = commandService.createCurrentUserCommand(
                request.deviceId(), request.templateKey(), request.parameters(), request.reason(),
                request.workOrderNo(), idempotencyKey,
                request.ttlSeconds(), request.timeoutSeconds(), traceId
        );
        URI location = UriComponentsBuilder.fromPath("/api/v1/commands/{id}")
                .buildAndExpand(command.id()).toUri();
        return ResponseEntity.created(location).body(command);
    }

    /**
     * 取消尚未开始发送的排队命令。
     *
     * @param commandId 命令标识
     * @param request 取消请求
     * @return 取消后的命令
     */
    @PostMapping("/commands/{id}/cancel")
    @SaCheckPermission("command:instance:cancel")
    public CommandResponse cancelCommand(
            @PathVariable("id") UUID commandId,
            @Valid @RequestBody CommandCancelRequest request
    ) {
        return commandService.cancelCurrentUserCommand(commandId, request.version(), request.reason());
    }

    /**
     * 命令取消请求。
     *
     * @param version 期望状态版本
     * @param reason 取消原因
     */
    public record CommandCancelRequest(
            @Min(0) long version,
            @NotBlank @Size(max = 500) String reason
    ) {
    }
}
