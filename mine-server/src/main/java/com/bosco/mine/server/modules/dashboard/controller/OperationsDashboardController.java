package com.bosco.mine.server.modules.dashboard.controller;

import com.bosco.mine.server.modules.dashboard.service.OperationsDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供登录后调度首页所需的矿区生产运行总览。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class OperationsDashboardController {

    private final OperationsDashboardService service;

    /**
     * 创建调度首页控制器。
     *
     * @param service 调度首页聚合服务
     */
    public OperationsDashboardController(OperationsDashboardService service) {
        this.service = service;
    }

    /**
     * 查询矿区调度运行总览。
     *
     * @return 调度运行总览
     */
    @GetMapping("/operations")
    public OperationsDashboardService.OperationsOverview operations() {
        return service.overview();
    }
}
