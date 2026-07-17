package com.bosco.mine.server.modules.alarm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import com.bosco.mine.server.modules.alarm.dto.AlarmAcknowledgeRequest;
import com.bosco.mine.server.modules.alarm.dto.AlarmCloseRequest;
import com.bosco.mine.server.modules.alarm.dto.AlarmEventResponse;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 提供告警查询、确认和恢复后关闭接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/alarms")
public class AlarmEventController {
    private final AlarmEventService service;

    /**
     * 创建告警中心控制器。
     *
     * @param service 告警服务
     */
    public AlarmEventController(AlarmEventService service) {
        this.service = service;
    }

    /** 分页查询告警。 */
    @GetMapping
    @SaCheckPermission("alarm:event:view")
    public PageResponse<AlarmEventResponse> findAlarms(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) AlarmStatus status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String ruleKey,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size
    ) {
        return service.find(active, status, severity, deviceId, ruleKey, from, to, page, size);
    }

    /** 读取告警详情。 */
    @GetMapping("/{id}")
    @SaCheckPermission("alarm:event:view")
    public AlarmEventResponse findAlarm(@PathVariable UUID id) {
        return service.findById(id);
    }

    /** 首次确认告警。 */
    @PostMapping("/{id}/acknowledgements")
    @SaCheckPermission("alarm:event:ack")
    public AlarmEventResponse acknowledge(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AlarmAcknowledgeRequest request
    ) {
        return service.acknowledgeCurrentUser(
                id, request.note(), idempotencyKey, request.version());
    }

    /** 关闭一个已经恢复的告警。 */
    @PostMapping("/{id}/close")
    @SaCheckPermission("alarm:event:close")
    public AlarmEventResponse close(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AlarmCloseRequest request
    ) {
        return service.closeCurrentUser(
                id, request.note(), idempotencyKey, request.version());
    }
}
