package com.bosco.mine.server.modules.alarm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import com.bosco.mine.server.modules.alarm.dto.AlarmEventResponse;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 为矿区场景提供强作用域的车载设备告警投影。
 *
 * <p>当前矿区设备归属以有效期车辆设备绑定为可信事实，因此本接口不扩大为
 * 固定设备的临时归属映射。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}/alarms")
public class MineAlarmQueryController {
    private final AlarmEventService service;

    /**
     * 创建矿区告警查询控制器。
     *
     * @param service 告警服务
     */
    public MineAlarmQueryController(AlarmEventService service) {
        this.service = service;
    }

    /**
     * 分页查询告警触发时归属当前矿区的车载设备告警。
     */
    @GetMapping
    @SaCheckPermission(value = {"mine:alarm:view", "alarm:event:view"}, mode = SaMode.OR)
    public PageResponse<AlarmEventResponse> findAlarms(
            @PathVariable UUID siteId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean closed,
            @RequestParam(required = false) AlarmStatus status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String deviceKeyword,
            @RequestParam(required = false) String ruleKey,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size
    ) {
        return service.findForSite(
                siteId, active, closed, status, severity, deviceId, deviceKeyword,
                ruleKey, from, to, page, size);
    }
}
