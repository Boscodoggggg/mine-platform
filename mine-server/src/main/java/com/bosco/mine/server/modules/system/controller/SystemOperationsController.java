package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.system.service.OperationsObservabilityService;
import com.bosco.mine.server.modules.system.service.SystemOperationsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供只向系统运维授权开放的按需运行状态检查。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemOperationsController {

    private final SystemOperationsService service;
    private final OperationsObservabilityService observabilityService;

    /**
     * 创建系统运行监控控制器。
     *
     * @param service              系统运行监控服务
     * @param observabilityService 历史可观测性聚合服务
     */
    public SystemOperationsController(
            SystemOperationsService service,
            OperationsObservabilityService observabilityService
    ) {
        this.service = service;
        this.observabilityService = observabilityService;
    }

    /**
     * 执行一次系统运行状态检查。
     *
     * @return 系统运行监控结果
     */
    @GetMapping("/operations")
    @SaCheckPermission("system:operations:view")
    public SystemOperationsService.SystemOperationsOverview operations() {
        return service.overview();
    }

    /**
     * 独立执行一个已注册检查项的即时探测。
     *
     * @param key 检查项稳定标识
     * @return 单项即时检查结果
     */
    @GetMapping("/operations/items/{key}/check")
    @SaCheckPermission("system:operations:view")
    public SystemOperationsService.OperationItem check(@PathVariable String key) {
        return service.check(key);
    }

    /**
     * 查询指定时间范围的运行历史总览。
     *
     * @param range 受控时间范围代码
     * @return 历史运行总览
     */
    @GetMapping("/operations/overview")
    @SaCheckPermission("system:operations:view")
    public OperationsObservabilityService.ObservabilityOverview overview(
            @RequestParam(defaultValue = "24h") String range
    ) {
        return observabilityService.overview(OperationsObservabilityService.TimeRange.fromCode(range));
    }

    /**
     * 查询单个已注册检查项的状态与延迟趋势。
     *
     * @param key   稳定检查项键
     * @param range 受控时间范围代码
     * @return 单项趋势
     */
    @GetMapping("/operations/items/{key}/series")
    @SaCheckPermission("system:operations:view")
    public OperationsObservabilityService.ObservabilitySeries series(
            @PathVariable String key,
            @RequestParam(defaultValue = "24h") String range
    ) {
        return observabilityService.series(key, OperationsObservabilityService.TimeRange.fromCode(range));
    }
}
