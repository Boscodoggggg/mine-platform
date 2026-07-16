package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.system.service.SystemOperationsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供只向系统运维授权开放的按需运行状态检查。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemOperationsController {

    private final SystemOperationsService service;

    /**
     * 创建系统运行监控控制器。
     *
     * @param service 系统运行监控服务
     */
    public SystemOperationsController(SystemOperationsService service) {
        this.service = service;
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
}
